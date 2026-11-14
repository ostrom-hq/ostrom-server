// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/RelayRoutes.kt; see NOTICE.
package run.ostrom.server.push

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelayRegisterRequest
import run.ostrom.core.api.models.PushRelayRegisterResponse
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

/** The largest request the relay reads: a token, an encrypted payload under the FCM limit and a few fields. */
public const val MAX_BODY_BYTES: Int = 8_192

/**
 * The relay's HTTP surface: `GET /healthz`, `POST /v1/instances` and `POST /v1/send`. Nothing about a request is
 * logged: not its token, its payload or its instance.
 *
 * @param relay The relay the routes answer with.
 */
public fun Application.relayModule(relay: Relay) {
    routing {
        get("/healthz") { call.respondText("ok") }
        post(PushRelay.REGISTER_PATH) { call.register(relay) }
        post(PushRelay.SEND_PATH) { call.send(relay) }
    }
}

private suspend fun ApplicationCall.register(relay: Relay) {
    val body = boundedBody() ?: return respond(HttpStatusCode.PayloadTooLarge)
    val id = decodeOrNull(PushRelayRegisterRequest.serializer(), body)?.let { relay.register(it.publicKey) }
    if (id == null) {
        respondJson(
            HttpStatusCode.BadRequest,
            OstromJson.encodeToString(PushRelaySendResponse("rejected", error = "invalid_key")),
        )
    } else {
        respondJson(HttpStatusCode.OK, OstromJson.encodeToString(PushRelayRegisterResponse(id)))
    }
}

private suspend fun ApplicationCall.send(relay: Relay) {
    val body = boundedBody() ?: return respond(HttpStatusCode.PayloadTooLarge)
    val answer = relay.send(
        request.headers[PushRelay.INSTANCE_HEADER],
        request.headers[PushRelay.TIMESTAMP_HEADER],
        request.headers[PushRelay.SIGNATURE_HEADER],
        body,
    ) { bytes -> decodeOrNull(PushRelaySendRequest.serializer(), bytes) }
    answer.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respondJson(HttpStatusCode.fromValue(answer.status), OstromJson.encodeToString(answer.body))
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, json: String) =
    respondText(json, ContentType.Application.Json, status)

/** The body read as [strategy], or null when it is not valid JSON of that shape. */
internal fun <T> decodeOrNull(strategy: DeserializationStrategy<T>, bytes: ByteArray): T? = try {
    OstromJson.decodeFromString(strategy, bytes.decodeToString())
} catch (_: IllegalArgumentException) {
    null
}

/** The body, or null when it is larger than [MAX_BODY_BYTES], declared or not. */
private suspend fun ApplicationCall.boundedBody(): ByteArray? {
    if ((request.contentLength() ?: 0) > MAX_BODY_BYTES) return null
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    return bytes.takeIf { it.size <= MAX_BODY_BYTES }
}
