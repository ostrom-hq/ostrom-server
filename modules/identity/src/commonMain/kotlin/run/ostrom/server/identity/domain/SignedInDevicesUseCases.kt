// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The kind of token a workstation holds: the CLI's, issued by `ostrom login` (device flow) or the launcher's login. A
 * device is one live CLI token; signing it out revokes the token.
 */
private val DEVICE_KIND: TokenKind = TokenKind.CLI

/**
 * Who sees and signs out every member's tokens of `ostrom login --admin` in an organization: those who may approve one,
 * for a token that leaked or a member who left.
 */
private val ELEVATED_TOKENS_MANAGERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)

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
 * [ListDevicesUseCase] on the tokens repositories.
 *
 * @param tokens The account-level tokens.
 * @param elevated The tokens of `ostrom login --admin`, inside their organization.
 * @param clock The clock.
 */
class ListDevicesUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val elevated: ElevatedTokensRepository,
    private val clock: Clock,
) : ListDevicesUseCase {
    override suspend fun invoke(userId: Uuid, member: OrgPrincipal?): List<ApiToken> {
        val now = clock.now()
        val devices = tokens.listLive(userId, setOf(DEVICE_KIND), now)
        if (member == null) return devices
        val owner = userId.takeIf { member.role !in ELEVATED_TOKENS_MANAGERS }
        return devices + elevated.listLive(member.orgId, owner, now)
    }
}

/** Signs a workstation out: its token stops working at once. */
interface SignOutDeviceUseCase {
    /**
     * Signs it out: one of the person's CLI tokens, or a token of `ostrom login --admin` in the organization the
     * request names ([member]), where it is bound. A member signs out their own; an owner or an admin any member's.
     *
     * @param userId The person.
     * @param deviceId The device (its CLI token, or its token of `ostrom login --admin`).
     * @param member The person in the organization the request names, or null when it names none.
     *
     * @throws DeviceNotFoundException When it is absent, another person's CLI token, another member's elevated token
     *   for a member, bound to another organization, neither kind of token, or already signed out.
     */
    suspend operator fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal? = null)
}

/**
 * [SignOutDeviceUseCase] on the tokens repositories. The audit names the person who signs it out (actor) and, for an
 * elevated token, its owner.
 *
 * @param tokens The account-level tokens.
 * @param elevated The tokens of `ostrom login --admin`, inside their organization.
 * @param audit The audit log.
 * @param clock The clock.
 */
class SignOutDeviceUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val elevated: ElevatedTokensRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : SignOutDeviceUseCase {
    override suspend fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal?) {
        // `revoke` refuses a token already revoked; the kind is checked first, so no other token is signed out here.
        if (tokens.get(userId, deviceId)?.kind == DEVICE_KIND) {
            if (!tokens.revoke(userId, deviceId, clock.nowMillis())) throw DeviceNotFoundException()
            val details = mapOf("kind" to "cli")
            audit.record(AuditEntry(null, userId, "token.revoked", "token", deviceId.toString(), details))
            return
        }
        if (member == null) throw DeviceNotFoundException()
        val token = elevated.get(member.orgId, deviceId) ?: throw DeviceNotFoundException()
        if (token.userId != userId && member.role !in ELEVATED_TOKENS_MANAGERS) throw DeviceNotFoundException()
        if (!elevated.revoke(member.orgId, deviceId, clock.nowMillis())) throw DeviceNotFoundException()
        val details = mapOf("kind" to "admin", "owner" to token.userId.toString())
        audit.record(AuditEntry(member.orgId, userId, "token.revoked", "token", deviceId.toString(), details))
    }
}
