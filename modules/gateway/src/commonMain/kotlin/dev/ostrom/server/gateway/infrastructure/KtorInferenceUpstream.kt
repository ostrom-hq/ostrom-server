// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.server.gateway.domain.ByteSource
import dev.ostrom.server.gateway.domain.InferenceUpstream
import dev.ostrom.server.gateway.domain.UpstreamAnswer
import dev.ostrom.server.gateway.domain.UpstreamRequest
import dev.ostrom.server.gateway.domain.UpstreamStalledException
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * [InferenceUpstream] on a Ktor client. The answer is never buffered: it is read chunk by chunk as the agent receives
 * it. No timeout on the whole call (a long generation streams for minutes), but a provider silent for [idle] is cut:
 * before its headers (the clock starts with the call, so the connection and the request count too) and between two
 * chunks (each chunk restarts it). The gateway counts it itself, because the client's socket timeout depends
 * on the engine (Curl, the binary's only one, does not apply it).
 *
 * @param client Without redirects (a redirect would carry the credential elsewhere).
 * @param idle How long the provider may stay silent.
 */
class KtorInferenceUpstream(private val client: HttpClient, private val idle: Duration) : InferenceUpstream {
    override suspend fun <T> exchange(request: UpstreamRequest, handle: suspend (UpstreamAnswer) -> T): T =
        coroutineScope {
            val answered = CompletableDeferred<Unit>()
            launch {
                // A failure here fails the scope, so the call stops and fails with it.
                if (withTimeoutOrNull(idle) { answered.await() } == null) throw UpstreamStalledException()
            }
            client.preparePost(request.url) {
                headers {
                    request.headers.filter { it.first != "content-type" }
                        .forEach { (name, value) -> append(name, value) }
                }
                setBody(TextContent(request.body, ContentType.Application.Json))
            }.execute { response ->
                answered.complete(Unit)
                val channel = response.bodyAsChannel()
                val headers = response.headers.entries()
                    .associate { (name, values) -> name.lowercase() to values.joinToString(",") }
                val body = ByteSource { buffer ->
                    withTimeoutOrNull(idle) { channel.readAvailable(buffer, 0, buffer.size) }
                        ?: throw UpstreamStalledException()
                }
                handle(UpstreamAnswer(response.status.value, headers, body))
            }
        }
}
