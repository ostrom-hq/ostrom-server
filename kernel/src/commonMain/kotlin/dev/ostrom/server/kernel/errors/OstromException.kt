// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.errors

/**
 * The kinds of failure a use case can report. The presentation layer maps each to one HTTP status
 * (`dev.ostrom.server.kernel.http.configureErrors`, CONVENTIONS section 3.2); the domain never speaks HTTP.
 *
 * @property type The stable `snake_case` key clients receive in `error.type`; never changed once published.
 */
enum class ErrorKind(val type: String) {
    INVALID_REQUEST("invalid_request"),
    UNAUTHORIZED("unauthorized"),
    PAYMENT_REQUIRED("payment_required"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
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
 * @property message The message sent to the client: never a secret, a token, a prompt or an upstream body.
 * @property type The `error.type` clients receive: the kind's own, unless the refusal needs a more precise stable key
 *   that a client acts on (`installation_not_yours`), with the kind's status.
 */
open class OstromException(
    val kind: ErrorKind,
    override val message: String,
    val type: String = kind.type,
) : Exception(message)
