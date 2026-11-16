// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.DaemonPresence
import run.ostrom.server.kernel.auth.DeviceRemovalListener
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The kind of token a workstation holds: the CLI's, issued by `ostrom login` (device flow) or the launcher's login. A
 * device is one installation of the CLI or the app (`devices.v1`) and the live tokens signed in on it; a CLI token from
 * before devices, or without an installation, is listed on its own and signing it out revokes the token.
 */
private val DEVICE_KIND: TokenKind = TokenKind.CLI

/** The tokens that are sign-ins of a registered device: the CLI's, and the app's when it named its installation. */
private val SIGN_IN_KINDS: Set<TokenKind> = setOf(DEVICE_KIND, TokenKind.MOBILE)

/**
 * Who sees and signs out every member's tokens of `ostrom login --admin` in an organization, and reads and renames a
 * member's devices: those who may approve one, for a token that leaked or a member who left.
 */
private val DEVICES_MANAGERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * A line of the listing of devices.
 *
 * @property id The device's id for a registered installation, the token's for any other line.
 * @property name Its name.
 * @property platform The system the installation reported at its latest sign-in; null on a line of a token.
 * @property osVersion The system's version.
 * @property createdAt When the installation was registered; null on a line of a token.
 * @property lastLoginAt Its latest sign-in; null on a line of a token.
 * @property lastSeenAt The latest call of its newest live token (a token's own on a line of a token).
 * @property activeSessions How many live CLI tokens a registered device holds; null on a line of a token.
 * @property current Whether the call came from it; null where the caller is no CLI sign-in nor a registered device's.
 * @property elevated The token of `ostrom login --admin` this line is, which has no device; null on any other line.
 * @property daemon A registered device's daemon (`runners.cloud.remote.daemon.v1`); null when it never polled or said
 *   it stopped, and on a line of a token.
 */
data class SignedInDevice(
    val id: Uuid,
    val name: String,
    val platform: String? = null,
    val osVersion: String? = null,
    val createdAt: Instant? = null,
    val lastLoginAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val activeSessions: Int? = null,
    val current: Boolean? = null,
    val elevated: ApiToken? = null,
    val daemon: DaemonPresence? = null,
)

/** Lists the workstations where the person is signed in. */
interface ListDevicesUseCase {
    /**
     * Lists them. A token of `ostrom login --admin` is bound to an organization and only read inside it: it is listed
     * when the request names that organization ([member]), with the tokens of every member for its owners and admins.
     *
     * @param userId The person.
     * @param member The person in the organization the request names, or null when it names none.
     * @param callerToken The CLI token the call is made with, for `current`; null for a browser session, and when
     *   another person's devices are read.
     *
     * @return Their registered devices (the latest sign-in first, those without a live token included), then their
     *   live CLI tokens without a device, then the organization's live elevated tokens, each newest first.
     */
    suspend operator fun invoke(userId: Uuid, member: OrgPrincipal? = null, callerToken: Uuid? = null):
        List<SignedInDevice>
}

/**
 * [ListDevicesUseCase] on the repositories.
 *
 * @param tokens The account-level tokens.
 * @param elevated The tokens of `ostrom login --admin`, inside their organization.
 * @param devices The registered devices.
 * @param clock The clock.
 */
class ListDevicesUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val elevated: ElevatedTokensRepository,
    private val devices: DevicesRepository,
    private val clock: Clock,
) : ListDevicesUseCase {
    override suspend fun invoke(userId: Uuid, member: OrgPrincipal?, callerToken: Uuid?): List<SignedInDevice> {
        val now = clock.now()
        val live = tokens.listLive(userId, SIGN_IN_KINDS, now)
        val currentDevice = live.firstOrNull { it.id == callerToken }?.deviceId
        val sessions = live.filter { it.deviceId != null }.groupBy { it.deviceId }
        val registered = devices.list(userId).map { device ->
            val own = sessions[device.id].orEmpty()
            SignedInDevice(
                id = device.id,
                name = device.name,
                platform = device.platform,
                osVersion = device.osVersion,
                createdAt = device.createdAt,
                lastLoginAt = device.lastLoginAt,
                // `live` is newest first: the first is the device's newest sign-in.
                lastSeenAt = own.firstOrNull()?.lastUsedAt,
                activeSessions = own.size,
                current = device.id == currentDevice,
                daemon = DaemonPresence.of(device.daemonSeenAt, device.daemonVersion, now, device.daemonFeatures),
            )
        }
        // An app token without a device (an app older than devices) stays among the tokens, not the devices.
        val alone = live.filter { it.deviceId == null && it.kind == DEVICE_KIND }.map {
            val current = (it.id == callerToken).takeIf { itself -> itself }
            SignedInDevice(it.id, it.name, lastSeenAt = it.lastUsedAt, current = current)
        }
        if (member == null) return registered + alone
        val owner = userId.takeIf { member.role !in DEVICES_MANAGERS }
        val elevatedLines = elevated.listLive(member.orgId, owner, now).map {
            SignedInDevice(it.id, it.name, lastSeenAt = it.lastUsedAt, elevated = it)
        }
        return registered + alone + elevatedLines
    }
}

/** Lists the devices of a member of the organization, for its owners and admins. */
interface ListMemberDevicesUseCase {
    /**
     * Lists them, as [ListDevicesUseCase] does for that member (`current` is never set).
     *
     * @param caller The caller in the organization the request names.
     * @param memberId The member whose devices are read.
     *
     * @return Their devices.
     *
     * @throws DevicesAdminRequiredException When the caller is neither an owner nor an admin.
     * @throws DeviceMemberNotFoundException When [memberId] names no member of that organization.
     */
    suspend operator fun invoke(caller: OrgPrincipal, memberId: String): List<SignedInDevice>
}

/**
 * [ListMemberDevicesUseCase] on the member directory and [ListDevicesUseCase].
 *
 * @param members Who the member is.
 * @param list Lists a person's devices.
 */
class ListMemberDevicesUseCaseImpl(
    private val members: MemberDirectory,
    private val list: ListDevicesUseCase,
) : ListMemberDevicesUseCase {
    override suspend fun invoke(caller: OrgPrincipal, memberId: String): List<SignedInDevice> {
        if (caller.role !in DEVICES_MANAGERS) throw DevicesAdminRequiredException()
        val userId = members.userIdOf(caller.orgId, memberId) ?: throw DeviceMemberNotFoundException()
        // The member's own view: their elevated tokens of this organization, whatever the caller's role.
        return list(userId, OrgPrincipal(caller.orgId, Uuid.parse(memberId), OrgRole.MEMBER))
    }
}

/** Renames a registered device. */
interface RenameDeviceUseCase {
    /**
     * Renames it: the person's own, or a member's for an owner or an admin of the organization the request names.
     *
     * @param userId The person.
     * @param deviceId The device.
     * @param name The new name, 1 to 120 characters without a control character.
     * @param member The person in the organization the request names, or null when it names none.
     * @param callerToken The CLI token the call is made with, for `current`; null for a browser session.
     *
     * @return The device as listed.
     *
     * @throws InvalidDeviceNameException When the name is out of bounds.
     * @throws DeviceNotFoundException When the device is absent, another person's (a member's, for someone who is not
     *   an owner or an admin of the organization it is read in), or a token listed on its own.
     */
    suspend operator fun invoke(
        userId: Uuid,
        deviceId: Uuid,
        name: String,
        member: OrgPrincipal? = null,
        callerToken: Uuid? = null,
    ): SignedInDevice
}

/**
 * [RenameDeviceUseCase] on the devices; the audit names the person who renames it, and its owner when it is not them.
 *
 * @param devices The registered devices.
 * @param members Whether the owner is a member of the organization.
 * @param list Lists the renamed device.
 * @param audit The audit log.
 */
class RenameDeviceUseCaseImpl(
    private val devices: DevicesRepository,
    private val members: MemberDirectory,
    private val list: ListDevicesUseCase,
    private val audit: AuditLog,
) : RenameDeviceUseCase {
    override suspend fun invoke(
        userId: Uuid,
        deviceId: Uuid,
        name: String,
        member: OrgPrincipal?,
        callerToken: Uuid?,
    ): SignedInDevice {
        val label = validatedDeviceName(name)
        val missing = { DeviceNotFoundException(DEVICE_NOT_FOUND) }
        val device = devices.get(deviceId) ?: throw missing()
        val orgId = managedIn(device, userId, member, members, missing)
        if (!devices.rename(deviceId, label)) throw missing()
        val details = ownerDetails(device, userId)
        audit.record(AuditEntry(orgId, userId, "device.renamed", "device", deviceId.toString(), details))
        val own = device.userId == userId
        return list(device.userId, null, callerToken.takeIf { own }).first { it.id == deviceId }
    }
}

/** The `error.type` of a registered device that is not there for the caller. */
private const val DEVICE_NOT_FOUND = "device_not_found"

/**
 * The organization a person acts in on a registered device: none on their own, the one the request names on a
 * member's when they own or administer it.
 *
 * @throws Exception What [missing] makes, when it is another person's and that does not hold.
 */
private suspend fun managedIn(
    device: RegisteredDevice,
    userId: Uuid,
    member: OrgPrincipal?,
    members: MemberDirectory,
    missing: () -> Exception,
): Uuid? {
    if (device.userId == userId) return null
    if (member == null || member.role !in DEVICES_MANAGERS || !members.hasUser(member.orgId, device.userId)) {
        throw missing()
    }
    return member.orgId
}

/** The audit details of an action on a device that is another person's: whose it is. */
private fun ownerDetails(device: RegisteredDevice, userId: Uuid): Map<String, String> =
    if (device.userId == userId) emptyMap() else mapOf("owner" to device.userId.toString())

/** Signs a workstation out: its token stops working at once. */
interface SignOutDeviceUseCase {
    /**
     * Signs it out: a registered device (every live token of it, and the device itself), one of the person's CLI
     * tokens from before devices, or a token of `ostrom login --admin` in the organization the request names
     * ([member]), where it is bound. A member signs out their own; an owner or an admin any member's.
     *
     * @param userId The person.
     * @param deviceId The device, its CLI token, or its token of `ostrom login --admin`.
     * @param member The person in the organization the request names, or null when it names none.
     *
     * @throws DeviceNotFoundException When it is absent, another person's CLI token or device (for someone who is not
     *   an owner or an admin of the organization the device's owner is a member of), another member's elevated token
     *   for a member, bound to another organization, neither kind of token, or already signed out.
     */
    suspend operator fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal? = null)
}

/**
 * [SignOutDeviceUseCase] on the repositories. The audit names the person who signs it out (actor) and, for an
 * elevated token or another person's device, its owner.
 *
 * @param tokens The account-level tokens.
 * @param elevated The tokens of `ostrom login --admin`, inside their organization.
 * @param devices The registered devices.
 * @param members Whether a device's owner is a member of the organization.
 * @param audit The audit log.
 * @param clock The clock.
 * @param removals Told once a registered device is removed (the runners module ends its remote sessions).
 */
// Its collaborators are the stores of the three kinds of workstation, the audit and who is told of a removal.
@Suppress("LongParameterList")
class SignOutDeviceUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val elevated: ElevatedTokensRepository,
    private val devices: DevicesRepository,
    private val members: MemberDirectory,
    private val audit: AuditLog,
    private val clock: Clock,
    private val removals: List<DeviceRemovalListener> = emptyList(),
) : SignOutDeviceUseCase {
    override suspend fun invoke(userId: Uuid, deviceId: Uuid, member: OrgPrincipal?) {
        // `revoke` refuses a token already revoked; the kind is checked first, so no other token is signed out here.
        if (tokens.get(userId, deviceId)?.kind == DEVICE_KIND) {
            if (!tokens.revoke(userId, deviceId, clock.nowMillis())) throw DeviceNotFoundException()
            val details = mapOf("kind" to "cli")
            audit.record(AuditEntry(null, userId, "token.revoked", "token", deviceId.toString(), details))
            return
        }
        val device = devices.get(deviceId)
        if (device != null) {
            val orgId = managedIn(device, userId, member, members) { DeviceNotFoundException() }
            if (!devices.remove(device.userId, deviceId, clock.nowMillis())) throw DeviceNotFoundException()
            val details = ownerDetails(device, userId)
            audit.record(AuditEntry(orgId, userId, "device.removed", "device", deviceId.toString(), details))
            removals.forEach { it.removed(device.userId, deviceId) }
            return
        }
        if (member == null) throw DeviceNotFoundException()
        val token = elevated.get(member.orgId, deviceId) ?: throw DeviceNotFoundException()
        if (token.userId != userId && member.role !in DEVICES_MANAGERS) throw DeviceNotFoundException()
        if (!elevated.revoke(member.orgId, deviceId, clock.nowMillis())) throw DeviceNotFoundException()
        val details = mapOf("kind" to "admin", "owner" to token.userId.toString())
        audit.record(AuditEntry(member.orgId, userId, "token.revoked", "token", deviceId.toString(), details))
    }
}
