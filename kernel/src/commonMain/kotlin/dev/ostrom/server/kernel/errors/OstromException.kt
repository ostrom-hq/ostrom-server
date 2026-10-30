package dev.ostrom.server.kernel.errors

/**
 * The kinds of failure a use case can report. The presentation layer maps each to one HTTP status
 * (`dev.ostrom.server.kernel.http.configureErrors`); the domain never speaks HTTP.
 *
 * @property code The stable machine-readable code clients receive in `error.code`.
 */
enum class ErrorKind(val code: String) {
    INVALID_REQUEST("invalid_request"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    RATE_LIMITED("rate_limited"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal_error"),
}

/**
 * A failure a use case reports on purpose, with a message safe to show to the client. Subclass it per domain error
 * (`class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")`). Any other
 * exception reaching the HTTP layer is an internal error: logged, and answered without its message.
 *
 * @property kind The kind of failure, which decides the HTTP status.
 */
open class OstromException(
    val kind: ErrorKind,
    message: String,
) : Exception(message)
