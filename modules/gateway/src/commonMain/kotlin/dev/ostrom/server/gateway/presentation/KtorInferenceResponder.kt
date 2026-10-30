// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.presentation

import dev.ostrom.server.gateway.domain.ByteSink
import dev.ostrom.server.gateway.domain.InferenceRefusal
import dev.ostrom.server.gateway.domain.InferenceResponder
import dev.ostrom.server.gateway.domain.WireProtocol
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

/**
 * [InferenceResponder] on a Ktor call: provider bytes go out as they arrive, flushed chunk by chunk; the gateway's own
 * errors take the shape of the agent's API, so agents handle them as they would the provider's.
 *
 * @param call The call.
 * @param clock For `retry-after`.
 */
class KtorInferenceResponder(private val call: ApplicationCall, private val clock: Clock) : InferenceResponder {
    override suspend fun refuse(protocol: WireProtocol, refusal: InferenceRefusal) {
        refusal.retryAt?.let {
            call.response.header("retry-after", (it - clock.now()).inWholeSeconds.coerceAtLeast(1).toString())
        }
        call.respondText(
            errorBody(protocol, refusal).toString(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(refusal.kind.status),
        )
    }

    override suspend fun stream(status: Int, headers: List<Pair<String, String>>, body: suspend (ByteSink) -> Unit) {
        val (types, others) = headers.partition { it.first == CONTENT_TYPE }
        others.forEach { (name, value) -> call.response.header(name, value) }
        val type = types.firstOrNull()?.second?.let(::contentTypeOrNull) ?: ContentType.Application.Json
        // The body is written by this coroutine and read by the engine from a channel, so the call returns only once
        // the provider's answer is read to its end (and metered); a writer block may run after the respond returns.
        coroutineScope {
            val channel = ByteChannel()
            launch { call.respond(ChannelContent(channel, type, HttpStatusCode.fromValue(status))) }
            body(
                ByteSink { bytes, count ->
                    channel.writeFully(bytes, 0, count)
                    channel.flush()
                },
            )
            channel.flushAndClose()
        }
    }

    /** A streamed answer: the engine reads [channel] as the gateway fills it. */
    private class ChannelContent(
        private val channel: ByteReadChannel,
        override val contentType: ContentType,
        override val status: HttpStatusCode,
    ) : OutgoingContent.ReadChannelContent() {
        override fun readFrom(): ByteReadChannel = channel
    }

    /** The error shapes. */
    companion object {
        private const val CONTENT_TYPE = "content-type"

        /** The error in the shape of the agent's API: Anthropic's for Messages, OpenAI's otherwise. */
        fun errorBody(protocol: WireProtocol, refusal: InferenceRefusal): JsonObject = buildJsonObject {
            if (protocol == WireProtocol.MESSAGES) {
                put("type", "error")
                putJsonObject("error") {
                    put("type", refusal.kind.anthropicType)
                    put("message", refusal.message)
                }
            } else {
                putJsonObject("error") {
                    put("message", refusal.message)
                    put("type", refusal.kind.openAiType)
                    put("code", refusal.kind.name.lowercase())
                }
            }
        }

        private fun contentTypeOrNull(value: String): ContentType? = try {
            ContentType.parse(value)
        } catch (_: BadContentTypeFormatException) {
            null
        }
    }
}
