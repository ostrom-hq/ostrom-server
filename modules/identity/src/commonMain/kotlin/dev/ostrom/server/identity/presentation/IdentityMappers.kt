// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.DEVICE_POLL_INTERVAL
import dev.ostrom.server.identity.domain.DevicePollResult
import dev.ostrom.server.identity.domain.IssuedToken
import dev.ostrom.server.identity.domain.Profile

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

/** Maps a poll result. */
fun DevicePollResult.toDeviceTokenResponse(): DeviceTokenResponse {
    val interval = DEVICE_POLL_INTERVAL.inWholeSeconds
    return when (this) {
        DevicePollResult.Pending -> DeviceTokenResponse("pending", interval = interval)
        DevicePollResult.SlowDown -> DeviceTokenResponse("slow_down", interval = interval * 2)
        DevicePollResult.Denied -> DeviceTokenResponse("denied", interval = interval)
        DevicePollResult.Expired -> DeviceTokenResponse("expired", interval = interval)
        is DevicePollResult.Approved -> DeviceTokenResponse(
            status = "approved",
            accessToken = token.secret,
            expiresAt = token.token.expiresAt?.toString(),
            scopes = token.token.scopes.sorted(),
            interval = interval,
        )
    }
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
