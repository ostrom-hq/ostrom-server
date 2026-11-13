// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.Logger
import io.github.smyrgeorge.sqlx4k.SQLError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/** The HTTP status of each [ErrorKind] (CONVENTIONS section 3.2). */
val ErrorKind.httpStatus: HttpStatusCode
    get() = when (this) {
        ErrorKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
        ErrorKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        ErrorKind.PAYMENT_REQUIRED -> HttpStatusCode.PaymentRequired
        ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
        ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
        ErrorKind.CONFLICT, ErrorKind.ORG_REQUIRED -> HttpStatusCode.Conflict
        ErrorKind.PAYLOAD_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        ErrorKind.UNSUPPORTED_MEDIA_TYPE -> HttpStatusCode.UnsupportedMediaType
        ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        ErrorKind.GONE -> HttpStatusCode.Gone
        ErrorKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        ErrorKind.INTERNAL -> HttpStatusCode.InternalServerError
    }

/**
 * Answers an error in the uniform [ApiError] format. Kernel use only: a route throws an [OstromException] instead,
 * so that the status pages below never rewrite its answer.
 *
 * @param kind The kind of failure, which decides the status and the type.
 * @param message A message safe to show to the client.
 */
internal suspend fun ApplicationCall.respondError(kind: ErrorKind, message: String) {
    respond(kind.httpStatus, ApiError(ErrorDetail(kind.type, message)))
}

/**
 * Installs the uniform error answers: an [OstromException] keeps its kind and message, a malformed request is a
 * 400, a body that is not JSON a 415, a unique-constraint violation a 409, an unknown route a 404, and anything
 * else a 500 whose cause is logged but never sent to the client. Cancellation is never turned into an answer.
 *
 * @param logger Where unexpected failures are logged.
 */
fun Application.configureErrors(logger: Logger) {
    install(StatusPages) {
        // One handler routed by type: on Kotlin/Native, StatusPages only matches a handler by the exact class, so
        // a subclass of OstromException (every domain error) would otherwise end up as a 500.
        exception<Throwable> { call, cause ->
            // A cancelled call (client gone, server stopping) is not a defect: let it propagate (CONVENTIONS 4.1).
            if (cause is CancellationException) throw cause
            call.answerFailure(cause, logger)
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(ErrorKind.NOT_FOUND, "Not found.")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError(ErrorDetail("method_not_allowed", "Method not allowed.")))
        }
    }
}

private suspend fun ApplicationCall.answerFailure(cause: Throwable, logger: Logger) {
    val fields = mapOf("request_id" to callId)
    when (cause) {
        is OstromException ->
            respond(cause.kind.httpStatus, ApiError(ErrorDetail(cause.type, cause.message, cause.requestBound)))
        is PayloadTooLargeException -> respondError(ErrorKind.PAYLOAD_TOO_LARGE, "The request body is too large.")
        // No converter for the request's Content-Type: `receive` throws CannotTransformContentToTypeException, a
        // sibling of UnsupportedMediaTypeException under this base (PayloadTooLargeException too, matched above).
        is ContentTransformationException ->
            respondError(ErrorKind.UNSUPPORTED_MEDIA_TYPE, "The request body must be JSON.")
        is BadRequestException -> {
            // Never the cause's message: a deserialization error quotes the request body.
            logger.debug("malformed request", fields)
            respondError(ErrorKind.INVALID_REQUEST, "The request is malformed.")
        }
        is NotFoundException -> respondError(ErrorKind.NOT_FOUND, "Not found.")
        is SQLError -> if (cause.kind == SQLError.Kind.UniqueViolation) {
            respondError(ErrorKind.CONFLICT, "The resource already exists.")
        } else {
            // Never the message: PostgreSQL quotes row values in it (AGENTS.md rule 9). The driver's code and the
            // SQLSTATE carry none, and tell a failure PostgreSQL never saw (a pool, a decoding) from one it reported.
            val codes = listOfNotNull("sql_code" to cause.code.name, cause.sqlState?.let { "sql_state" to it })
            logger.error(
                "database failure",
                fields + mapOf("error" to "SQLError", "sql_kind" to cause.kind.name) + codes,
            )
            respondError(ErrorKind.INTERNAL, "Internal error.")
        }
        else -> {
            logger.error("unexpected failure", fields + mapOf("error" to cause::class.simpleName))
            respondError(ErrorKind.INTERNAL, "Internal error.")
        }
    }
}
