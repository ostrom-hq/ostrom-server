// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.http

import kotlinx.serialization.Serializable

/**
 * The body of every error answer: `{"error":{"type":"not_found","message":"…"}}` (CONVENTIONS section 3.3). The
 * request id is in the `X-Request-Id` header of every answer. Moves to ostrom-core's `api` with the rest of the
 * contract once it publishes `ApiError`.
 *
 * @property error The failure.
 */
@Serializable
data class ApiError(val error: ErrorDetail)

/**
 * A failure as clients see it.
 *
 * @property type A stable `snake_case` key clients switch on ([run.ostrom.server.kernel.errors.ErrorKind.type]).
 * @property message A human-readable message, safe to display.
 * @property requestBound On a `step_up_required`, true when the phone must unlock again for this very request
 *   ([run.ostrom.server.kernel.errors.OstromException.requestBound]); absent otherwise.
 */
@Serializable
data class ErrorDetail(
    val type: String,
    val message: String,
    val requestBound: Boolean? = null,
)
