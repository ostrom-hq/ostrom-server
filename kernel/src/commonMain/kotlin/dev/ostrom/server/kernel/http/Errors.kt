package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.Logger
import io.github.smyrgeorge.sqlx4k.SQLError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/** The HTTP status of each [ErrorKind]. */
val ErrorKind.httpStatus: HttpStatusCode
    get() = when (this) {
        ErrorKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
        ErrorKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
        ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
        ErrorKind.CONFLICT -> HttpStatusCode.Conflict
        ErrorKind.PAYLOAD_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        ErrorKind.UNSUPPORTED_MEDIA_TYPE -> HttpStatusCode.UnsupportedMediaType
        ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        ErrorKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        ErrorKind.INTERNAL -> HttpStatusCode.InternalServerError
    }

/**
 * Answers an error in the uniform [ApiError] format. Kernel use only: a route throws an [OstromException] instead,
 * so that the status pages below never rewrite its answer.
 *
 * @param kind The kind of failure, which decides the status.
 * @param message A message safe to show to the client.
 */
internal suspend fun ApplicationCall.respondError(kind: ErrorKind, message: String) {
    respond(kind.httpStatus, ApiError(ApiErrorDetail(kind.code, message, callId)))
}

/**
 * Installs the uniform error answers: an [OstromException] keeps its kind and message, a malformed request is a
 * 400, a body that is not JSON a 415, a unique-constraint violation a 409, an unknown route a 404, and anything else a 500 whose cause is logged
 * but never sent to the client. Cancellation is never turned into an answer.
 *
 * @param logger Where unexpected failures are logged.
 */
fun Application.configureErrors(logger: Logger) {
    install(StatusPages) {
        exception<CancellationException> { _, cause -> throw cause }
        exception<OstromException> { call, cause ->
            call.respondError(cause.kind, cause.message ?: cause.kind.code)
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondError(ErrorKind.UNSUPPORTED_MEDIA_TYPE, "The request body must be JSON.")
        }
        exception<BadRequestException> { call, cause ->
            logger.debug("malformed request", mapOf("request_id" to call.callId, "cause" to (cause.cause ?: cause).message))
            call.respondError(ErrorKind.INVALID_REQUEST, "The request is malformed.")
        }
        exception<NotFoundException> { call, _ ->
            call.respondError(ErrorKind.NOT_FOUND, "Not found.")
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(ErrorKind.PAYLOAD_TOO_LARGE, "The request body is too large.")
        }
        exception<SQLError> { call, cause ->
            if (cause.kind == SQLError.Kind.UniqueViolation) {
                call.respondError(ErrorKind.CONFLICT, "The resource already exists.")
            } else {
                logger.error("database failure", mapOf("request_id" to call.callId), cause)
                call.respondError(ErrorKind.INTERNAL, "Internal error.")
            }
        }
        exception<Throwable> { call, cause ->
            logger.error("unexpected failure", mapOf("request_id" to call.callId), cause)
            call.respondError(ErrorKind.INTERNAL, "Internal error.")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(ErrorKind.NOT_FOUND, "Not found.")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError(ApiErrorDetail("method_not_allowed", "Method not allowed.", call.callId)))
        }
    }
}
