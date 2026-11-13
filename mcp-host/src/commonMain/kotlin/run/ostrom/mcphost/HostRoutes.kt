// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * The host's HTTP surface: host protocol v1 behind the token (`/v1/…`), and the unauthenticated probes `/health` (the
 * host answers) and `/ready` (not draining, launcher commands present). Only the server's pods reach it (the chart's
 * NetworkPolicy).
 *
 * @param config The host's settings.
 * @param supervisor The processes ([Supervisor]).
 * @param unexpected Told the class of an unexpected error (never its message, which could quote the request).
 */
fun Application.mcpHost(config: HostConfig, supervisor: HostService, unexpected: (String) -> Unit) {
    val expected = sha256(config.token)
    routing {
        get("/health") { call.respondText("ok") }
        get("/ready") {
            if (supervisor.ready()) {
                call.respondText("ready")
            } else {
                call.respondText("not ready", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        route(HostProtocol.OPS) {
            // A closed connection is the relay's cancellation: the call's coroutine is cancelled, and the process told.
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            post {
                val request = call.admit(config, expected, OpsRequest.serializer()) ?: return@post
                call.handle(unexpected) { call.json(OpsResponse.serializer(), supervisor.op(request)) }
            }
        }
        post(HostProtocol.WARM) {
            val request = call.admit(config, expected, WarmRequest.serializer()) ?: return@post
            call.handle(unexpected) {
                call.json(KeyState.serializer(), supervisor.warm(request), HttpStatusCode.Accepted)
            }
        }
        post(HostProtocol.RETAIN) {
            val request = call.admit(config, expected, RetainRequest.serializer()) ?: return@post
            call.handle(unexpected) {
                call.json(RetainResponse.serializer(), RetainResponse(supervisor.retain(request.keys)))
            }
        }
        post(HostProtocol.STOP) {
            val request = call.admit(config, expected, StopRequest.serializer()) ?: return@post
            call.handle(unexpected) {
                call.json(StopResponse.serializer(), StopResponse(supervisor.stop(request.key)))
            }
        }
        get(HostProtocol.STATUS) {
            if (!call.authorized(expected)) return@get
            call.json(HostStatus.serializer(), supervisor.status())
        }
    }
}

/** Token (constant time), protocol version, bounded body and its decoding; null once a refusal was answered. */
private suspend fun <T> ApplicationCall.admit(config: HostConfig, expected: ByteArray, serializer: KSerializer<T>): T? {
    if (!authorized(expected)) return null
    val bytes = receiveChannel().readRemaining(config.maxRequestBytes.toLong() + 1).readByteArray()
    return if (bytes.size > config.maxRequestBytes) {
        val message = "The request exceeds ${config.maxRequestBytes} bytes."
        refuse(HttpStatusCode.PayloadTooLarge, HostErrors.BAD_REQUEST, message)
        null
    } else {
        decode(serializer, bytes)
    }
}

private suspend fun <T> ApplicationCall.decode(serializer: KSerializer<T>, bytes: ByteArray): T? = try {
    HostProtocol.json.decodeFromString(serializer, bytes.decodeToString())
} catch (_: SerializationException) {
    refuse(HttpStatusCode.BadRequest, HostErrors.BAD_REQUEST, "The request is not valid host protocol JSON.")
    null
}

private suspend fun ApplicationCall.authorized(expected: ByteArray): Boolean {
    val presented = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
    val protocol = request.headers[HostProtocol.HEADER]
    val refusal = when {
        // Hashing both sides gives equal lengths, so the comparison leaks neither the token nor its length.
        presented == null || !constantTimeEquals(sha256(presented), expected) ->
            HttpStatusCode.Unauthorized to HostError(HostErrors.UNAUTHORIZED, "A valid host token is required.")
        protocol != HostProtocol.VERSION -> HttpStatusCode.BadRequest to HostError(
            HostErrors.UNSUPPORTED_PROTOCOL,
            "This Ostrom MCP host speaks host protocol ${HostProtocol.VERSION}, " +
                "the request sent ${protocol ?: "none"}: deploy the server and the MCP host at the same version.",
        )
        else -> null
    }
    refusal?.let { (status, error) -> refuse(status, error.code, error.message) }
    return refusal == null
}

private suspend fun ApplicationCall.handle(unexpected: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (failure: HostFailure) {
        val status = if (failure.code == HostErrors.SHUTTING_DOWN) {
            HttpStatusCode.ServiceUnavailable
        } else {
            HttpStatusCode.BadRequest
        }
        refuse(status, failure.code, failure.message)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Neither the message nor the stack trace: they could quote a path or a value of the request.
        unexpected(error::class.simpleName ?: "Exception")
        refuse(HttpStatusCode.InternalServerError, "internal", "The MCP host failed unexpectedly.")
    }
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String, message: String) =
    json(HostErrorResponse.serializer(), HostErrorResponse(HostError(code, message)), status)

/**
 * The body of a refusal.
 *
 * @property error The refusal.
 */
@Serializable
private data class HostErrorResponse(val error: HostError)

private suspend fun <T> ApplicationCall.json(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(HostProtocol.json.encodeToString(serializer, value), ContentType.Application.Json, status)

private const val BEARER = "Bearer "

private fun sha256(value: String): ByteArray =
    CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(value.encodeToByteArray())

private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    var difference = left.size xor right.size
    for (index in 0 until minOf(left.size, right.size)) {
        difference = difference or (left[index].toInt() xor right[index].toInt())
    }
    return difference == 0
}
