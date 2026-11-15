// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.CloudEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How long an `EventSource` waits before it reconnects. */
private const val RETRY_MILLIS = 2_000

/** Server-sent events: the frames [body] sends, flushed each time; a refusal on the way ends it with `denied`. */
internal suspend fun ApplicationCall.stream(body: suspend (suspend (String) -> Unit) -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    // nginx (the web image proxies /api/) buffers responses unless told not to: the events would arrive late.
    response.header("X-Accel-Buffering", "no")
    respondBytesWriter(ContentType.Text.EventStream) { follow(body) }
}

internal suspend fun ByteWriteChannel.follow(body: suspend (suspend (String) -> Unit) -> Unit) {
    suspend fun send(text: String) {
        writeStringUtf8(text)
        flush()
    }
    try {
        send("retry: $RETRY_MILLIS\n\n")
        try {
            body(::send)
        } catch (denied: OstromException) {
            // The caller lost the session meanwhile: the stream ends with the refusal's key, nothing else.
            send("event: denied\ndata: ${buildJsonObject { put("type", denied.type) }}\n\n")
        }
    } catch (_: IOException) {
        // The client closed the stream: there is nobody left to answer, the response simply ends.
    }
}

internal fun eventFrame(event: CloudEvent): String {
    val data = Json.encodeToString(CloudEventDto.serializer(), event.toDto())
    return "id: ${event.seq}\nevent: ${event.type.wire}\ndata: $data\n\n"
}
