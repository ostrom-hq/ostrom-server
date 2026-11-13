// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.errors

/**
 * The kinds of failure a use case can report. The presentation layer maps each to one HTTP status
 * (`run.ostrom.server.kernel.http.configureErrors`, CONVENTIONS section 3.2); the domain never speaks HTTP.
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
    ORG_REQUIRED("org_required"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    RATE_LIMITED("rate_limited"),
    GONE("gone"),
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
 * @property type The `error.type` clients receive: the kind's, unless the contract names a finer key for this failure
 *   (`step_up_required`, a 403; `installation_not_yours`, a 403; `installation_pending`, a 503). Stable once
 *   published, like [ErrorKind.type].
 * @property requestBound True on a `step_up_required` that only an unlock of the phone bound to this very request
 *   lifts (`error.requestBound`, Q-APP-STEPUP-RISK); null, and absent from the answer, otherwise.
 */
open class OstromException(
    val kind: ErrorKind,
    override val message: String,
    val type: String = kind.type,
    val requestBound: Boolean? = null,
) : Exception(message)
