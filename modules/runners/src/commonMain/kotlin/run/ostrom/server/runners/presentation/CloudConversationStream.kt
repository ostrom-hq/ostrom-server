// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudConversationService
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
import kotlin.uuid.Uuid

/** How long an `EventSource` waits before it reconnects. */
private const val RETRY_MILLIS = 2_000

/** A comment frame, so proxies keep a quiet stream open. */
internal const val KEEP_ALIVE = ": keep-alive\n\n"

/** The last frame, once the session ended. */
internal const val END = "event: end\ndata: {}\n\n"

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

/**
 * The transcript after [after] as frames: every new page, a keep-alive while it is quiet, `end` once the session ended.
 *
 * @param caller Who reads it, checked again on the way.
 * @param id The session.
 * @param after The last event the client has.
 * @param send Writes a frame.
 */
internal suspend fun CloudConversationService.frames(
    caller: Caller,
    id: Uuid,
    after: Long,
    send: suspend (String) -> Unit,
) {
    follow(
        caller,
        id,
        after,
        emit = { events -> send(events.joinToString("") { eventFrame(it) }) },
        idle = { send(KEEP_ALIVE) },
    )
    send(END)
}

internal fun eventFrame(event: CloudEvent): String {
    val data = Json.encodeToString(CloudEventDto.serializer(), event.toDto())
    return "id: ${event.seq}\nevent: ${event.type.wire}\ndata: $data\n\n"
}
