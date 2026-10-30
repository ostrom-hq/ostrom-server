package dev.ostrom.server.kernel.http

import kotlinx.serialization.Serializable

/**
 * The body of every error answer: `{"error":{"code":"not_found","message":"…","requestId":"…"}}`.
 * To move to ostrom-core with the rest of the API contract once it is published.
 *
 * @property error The failure.
 */
@Serializable
data class ApiError(val error: ApiErrorDetail)

/**
 * A failure as clients see it.
 *
 * @property code A stable machine-readable code ([dev.ostrom.server.kernel.errors.ErrorKind.code]).
 * @property message A human-readable message, safe to display.
 * @property requestId The request id (`X-Request-Id`), to find the request in the server logs.
 */
@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
    val requestId: String? = null,
)
