// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Devices as TeamCode's (docs/devices.md): one device per installation of the CLI (`devices.v1`), keyed by person and
// installation, kept across the sign-ins that are its tokens.

/** The most devices a person registers; another installation's sign-in is refused past it. */
const val MAX_REGISTERED_DEVICES = 50

/** The longest device name. */
const val MAX_DEVICE_NAME = 120

/** The longest system version. */
const val MAX_OS_VERSION = 64

/** The systems a CLI reports, in contract order. */
val DEVICE_PLATFORMS: List<String> = listOf("macos", "windows", "linux", "ios", "android")

/** An installation id: a lowercase UUID. */
private val INSTALLATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The installation is not a lowercase UUID (400 `invalid_installation`). */
class InvalidInstallationException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "An installation identifier must be a lowercase UUID.",
    "invalid_installation",
)

/** The device name is blank, longer than [MAX_DEVICE_NAME] or holds a control character (400 `invalid_device_name`). */
class InvalidDeviceNameException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Use a device name of 1-$MAX_DEVICE_NAME characters without control characters.",
    "invalid_device_name",
)

/** The platform is not one of [DEVICE_PLATFORMS] (400 `invalid_platform`). */
class InvalidPlatformException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Use a platform among ${DEVICE_PLATFORMS.joinToString(", ")}.",
    "invalid_platform",
)

/** The system version is sent without a platform, or is not 1 to [MAX_OS_VERSION] printable characters. */
class InvalidOsVersionException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Send an operating system version of 1-$MAX_OS_VERSION printable characters, with its platform.",
    "invalid_os_version",
)

/** The person holds [MAX_REGISTERED_DEVICES] devices already (409 `device_limit`). */
class DeviceLimitException : OstromException(
    ErrorKind.CONFLICT,
    "Remove an unused device before registering another installation.",
    "device_limit",
)

/** Only an owner or an admin reads the devices of another member (403). */
class DevicesAdminRequiredException :
    OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin reads another member's devices.")

/** The member is unknown, or another organization's (404). */
class DeviceMemberNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Member not found.")

/**
 * What a CLI says of its installation at `ostrom login`, as the contract's `DeviceLoginStart` sends it.
 *
 * @property installationId The installation, a random lowercase UUID kept by the CLI.
 * @property deviceName The name a device this sign-in registers carries; the client's name when absent.
 * @property platform The system, one of [DEVICE_PLATFORMS].
 * @property osVersion The system's version, only with [platform].
 */
data class DeviceLoginIdentity(
    val installationId: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
)

/**
 * Checks what a CLI says of its installation, trimmed. Without an installation nothing is registered, so the rest is
 * ignored (a client older than the server's devices sends none).
 *
 * @param identity What was sent.
 *
 * @return It validated, or null when it names no installation.
 *
 * @throws InvalidInstallationException, InvalidDeviceNameException, InvalidPlatformException,
 *   InvalidOsVersionException When a value is out of its bounds.
 */
fun validatedInstallation(identity: DeviceLoginIdentity?): DeviceLoginIdentity? {
    val installation = identity?.installationId ?: return null
    if (!INSTALLATION_ID.matches(installation)) throw InvalidInstallationException()
    val name = identity.deviceName?.let(::validatedDeviceName)
    val platform = identity.platform?.also { if (it !in DEVICE_PLATFORMS) throw InvalidPlatformException() }
    val version = identity.osVersion?.also { checkOsVersion(platform, it) }
    return DeviceLoginIdentity(installation, name, platform, version)
}

/** A system version is 1 to [MAX_OS_VERSION] printable characters, sent with its platform. */
private fun checkOsVersion(platform: String?, version: String) {
    val printable = version.none { it.isISOControl() || it.isWhitespace() && it != ' ' }
    val valid = platform != null && version.isNotBlank() && version.length <= MAX_OS_VERSION && printable
    if (!valid) throw InvalidOsVersionException()
}

/**
 * A device name, trimmed.
 *
 * @param name The name as sent.
 *
 * @return The name.
 *
 * @throws InvalidDeviceNameException When it is blank, longer than [MAX_DEVICE_NAME] or holds a control character.
 */
fun validatedDeviceName(name: String): String {
    val label = name.trim()
    if (label.isEmpty() || label.length > MAX_DEVICE_NAME || label.any { it.isISOControl() }) {
        throw InvalidDeviceNameException()
    }
    return label
}

/**
 * A registered installation of the CLI.
 *
 * @property id The device's id, kept across its sign-ins.
 * @property userId Its owner.
 * @property installationId The installation (never returned by the API).
 * @property name Its name, set at the first sign-in or by its owner.
 * @property platform The system reported at its latest sign-in that reported one.
 * @property osVersion The system's version reported with [platform].
 * @property createdAt When it was registered.
 * @property lastLoginAt Its latest sign-in.
 */
data class RegisteredDevice(
    val id: Uuid,
    val userId: Uuid,
    val installationId: String,
    val name: String,
    val platform: String?,
    val osVersion: String?,
    val createdAt: Instant,
    val lastLoginAt: Instant,
)

/**
 * The installation an approved login signs in, as the token's issue registers it.
 *
 * @property installationId The installation.
 * @property name The name a new device carries; an existing device keeps its own.
 * @property platform The system to record, when the CLI reported one.
 * @property osVersion Its version.
 */
data class DeviceSignInRequest(
    val installationId: String,
    val name: String,
    val platform: String?,
    val osVersion: String?,
)

/**
 * What a sign-in on an installation did.
 *
 * @property device The device the token belongs to.
 * @property registered True when this sign-in registered it.
 */
data class DeviceSignIn(val device: RegisteredDevice, val registered: Boolean)

/** The registered devices. */
interface DevicesRepository {
    /**
     * Reads the device of a person's installation.
     *
     * @param userId The person.
     * @param installationId The installation.
     *
     * @return The device, or null when it is not registered.
     */
    suspend fun find(userId: Uuid, installationId: String): RegisteredDevice?

    /**
     * Counts a person's devices, up to [MAX_REGISTERED_DEVICES].
     *
     * @param userId The person.
     *
     * @return The count, capped.
     */
    suspend fun count(userId: Uuid): Int

    /**
     * Reads a device, whoever owns it.
     *
     * @param id The device.
     *
     * @return The device, or null when it is absent.
     */
    suspend fun get(id: Uuid): RegisteredDevice?

    /**
     * Lists a person's devices.
     *
     * @param userId The person.
     *
     * @return At most [MAX_REGISTERED_DEVICES], the latest sign-in first.
     */
    suspend fun list(userId: Uuid): List<RegisteredDevice>

    /**
     * Renames a device.
     *
     * @param id The device.
     * @param name Its new name, validated.
     *
     * @return True when the device exists.
     */
    suspend fun rename(id: Uuid, name: String): Boolean

    /**
     * Removes a device: every live token of it is revoked and the row deleted, in one transaction that no sign-in of
     * the person runs through at the same time.
     *
     * @param userId The device's owner.
     * @param id The device.
     * @param at When.
     *
     * @return True when the device existed and is gone.
     */
    suspend fun remove(userId: Uuid, id: Uuid, at: Instant): Boolean
}

/** The directory of a server without the orgs module: nobody is a member, so no one reads another person's devices. */
internal object NoMemberDirectory : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}
