// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The kind of token a workstation holds: the CLI's, issued by `ostrom login` (device flow) or the launcher's login. A
 * device is one live CLI token; signing it out revokes the token.
 */
private val DEVICE_KIND: TokenKind = TokenKind.CLI

/** Who sees every member's tokens of `ostrom login --admin` in an organization: those who may approve one. */
private val ELEVATED_TOKENS_VIEWERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/** Lists the workstations where the person is signed in. */
interface ListDevicesUseCase {
    /**
     * Lists them. A token of `ostrom login --admin` is bound to an organization and only read inside it: it is listed
     * when the request names that organization ([member]), with the tokens of every member for its owners and admins.
     *
     * @param userId The person.
     * @param member The person in the organization the request names, or null when it names none.
     *
     * @return Their live CLI tokens, then the organization's live elevated tokens, each newest first.
     */
    suspend operator fun invoke(userId: Uuid, member: OrgPrincipal? = null): List<ApiToken>
}

/**
 * [ListDevicesUseCase] on the tokens repository.
 *
 * @param tokens The tokens.
 * @param clock The clock.
 */
class ListDevicesUseCaseImpl(private val tokens: ApiTokensRepository, private val clock: Clock) : ListDevicesUseCase {
    override suspend fun invoke(userId: Uuid, member: OrgPrincipal?): List<ApiToken> {
        val now = clock.now()
        val devices = tokens.listLive(userId, setOf(DEVICE_KIND), now)
        if (member == null) return devices
        val owner = userId.takeIf { member.role !in ELEVATED_TOKENS_VIEWERS }
        return devices + tokens.listLiveElevated(member.orgId, owner, now)
    }
}

/** Signs one of the person's workstations out: its CLI token stops working at once. */
interface SignOutDeviceUseCase {
    /**
     * Signs it out. A token of `ostrom login --admin` is only signed out by its owner, in the organization the request
     * names ([member]), where it is bound.
     *
     * @param userId The person.
     * @param deviceId The device (its CLI token, or its token of `ostrom login --admin`).
     * @param member The person in the organization the request names, or null when it names none.
     *
     * @throws DeviceNotFoundException When it is absent, another person's, neither kind of token, bound to another
     *   organization, or already signed out.
     */
    suspend operator fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal? = null)
}

/**
 * [SignOutDeviceUseCase] on the tokens repository.
 *
 * @param tokens The tokens.
 * @param audit The audit log.
 * @param clock The clock.
 */
class SignOutDeviceUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : SignOutDeviceUseCase {
    override suspend fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal?) {
        // `revoke` refuses a token already revoked; the kind is checked first, so no other token is signed out here.
        val orgId = when {
            tokens.get(userId, deviceId)?.kind == DEVICE_KIND -> null
            member != null && tokens.get(userId, deviceId, member.orgId)?.kind == TokenKind.ADMIN -> member.orgId
            else -> throw DeviceNotFoundException()
        }
        val kind = if (orgId == null) DEVICE_KIND else TokenKind.ADMIN
        if (!tokens.revoke(userId, deviceId, clock.nowMillis(), orgId)) throw DeviceNotFoundException()
        val details = mapOf("kind" to kind.name.lowercase())
        audit.record(AuditEntry(orgId, userId, "token.revoked", "token", deviceId.toString(), details))
    }
}
