// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.DevicePollResult
import dev.ostrom.server.identity.domain.IssuedToken
import dev.ostrom.server.identity.domain.Profile
import dev.ostrom.server.kernel.http.ApiError
import dev.ostrom.server.kernel.http.ErrorDetail
import io.ktor.http.HttpStatusCode

/** Maps the profile; [csrfToken] only for a browser session. */
fun Profile.toProfileResponse(csrfToken: String?) = ProfileResponse(
    id = user.id.toString(),
    login = user.login,
    displayName = user.displayName,
    primaryEmail = user.primaryEmail,
    avatarUrl = user.avatarUrl,
    createdAt = user.createdAt.toString(),
    emails = emails.map { EmailResponse(it.email, it.verified) },
    providers = issuers,
    csrfToken = csrfToken,
)

/** Maps a token, without its secret. */
fun ApiToken.toTokenResponse() = TokenResponse(
    id = id.toString(),
    name = name,
    kind = kind.name.lowercase(),
    scopes = scopes.sorted(),
    hint = hint,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt?.toString(),
    lastUsedAt = lastUsedAt?.toString(),
)

/** Maps an issued token, with its secret. */
fun IssuedToken.toCreatedTokenResponse() = CreatedTokenResponse(token.toTokenResponse(), secret)

/**
 * Maps an approved poll to the contract's `AuthToken`.
 *
 * @param login The login of the user who approved.
 */
fun DevicePollResult.Approved.toAuthTokenResponse(login: String) = AuthTokenResponse(
    token = token.secret,
    login = login,
    expiresAt = token.token.expiresAt?.toString(),
    scopes = token.token.scopes.sorted(),
)

/** 428 Precondition Required: the contract's « authorization pending ». */
private val AUTHORIZATION_PENDING = HttpStatusCode(428, "Precondition Required")

/** Not yet (428), or never (400): the contract's `pollDeviceLogin`, with RFC 8628 § 3.5's error codes. */
private val DEVICE_POLL_REFUSALS: Map<DevicePollResult, Pair<HttpStatusCode, ErrorDetail>> = mapOf(
    DevicePollResult.Pending to
        (AUTHORIZATION_PENDING to ErrorDetail("authorization_pending", "Waiting for the sign-in to be approved.")),
    DevicePollResult.SlowDown to
        (HttpStatusCode.BadRequest to ErrorDetail("slow_down", "Polling too fast: wait longer between polls.")),
    DevicePollResult.Denied to
        (HttpStatusCode.BadRequest to ErrorDetail("access_denied", "The sign-in was denied.")),
    DevicePollResult.Expired to
        (HttpStatusCode.BadRequest to ErrorDetail("expired_token", "The code expired or was used: start again.")),
)

/**
 * The status and error of a poll that gives no token.
 *
 * @param result Anything but [DevicePollResult.Approved].
 * @return The status and the error body.
 */
fun devicePollRefusal(result: DevicePollResult): Pair<HttpStatusCode, ApiError> {
    val (status, detail) = DEVICE_POLL_REFUSALS.getValue(result)
    return status to ApiError(detail)
}

/** Maps an audit event. */
fun AuditEvent.toAuditEventResponse() = AuditEventResponse(
    id = id.toString(),
    sequence = sequence,
    orgId = entry.orgId?.toString(),
    actorId = entry.actorId?.toString(),
    action = entry.action,
    targetType = entry.targetType,
    targetId = entry.targetId,
    details = entry.details,
    occurredAt = occurredAt.toString(),
    previousHash = previousHash,
    hash = hash,
    signature = signature,
    keyId = keyId,
)

/**
 * Maps a page of audit events.
 *
 * @param limit The requested page size: a full page may have a next one.
 */
fun List<AuditEvent>.toAuditEventsResponse(limit: Int) = AuditEventsResponse(
    events = map { it.toAuditEventResponse() },
    nextBefore = if (size >= limit.coerceIn(1, MAX_AUDIT_PAGE)) last().sequence else null,
)

/** The largest page of audit events, as the use case clamps it. */
private const val MAX_AUDIT_PAGE = 200
