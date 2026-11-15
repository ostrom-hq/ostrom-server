// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * A person's device (`devices.v1`) as another module shows it: an installation of the CLI, kept across its sign-ins.
 *
 * @property id The device.
 * @property userId The person it belongs to.
 * @property name Its name as it is now.
 * @property platform Its system (`macos`, `windows`, `linux`…), null until its installation reported one.
 * @property osVersion The system's version reported with [platform].
 */
data class SignedInDevice(
    val id: Uuid,
    val userId: Uuid,
    val name: String,
    val platform: String?,
    val osVersion: String?,
)

/**
 * The devices people sign in from (TeamCode's `Principal.deviceId`): a remote session runs on the device of its
 * owner's sign-in, and only there. The identity module binds it; without it, no credential is a device's and a module
 * that needs one refuses (`remote_requires_device`).
 */
interface SignedInDevices {
    /**
     * The device a credential is a sign-in of.
     *
     * @param principal Who presented it.
     *
     * @return The device, or null for anything but a person's live CLI sign-in on a registered installation (a browser
     *   session, the app, a personal token, a service key, an agent, a CLI token from before devices).
     */
    suspend fun deviceOf(principal: AuthenticatedPrincipal): Uuid?

    /**
     * Devices as they are now.
     *
     * @param ids The devices.
     *
     * @return Each one that still exists, by id; a removed one is absent.
     */
    suspend fun devices(ids: Collection<Uuid>): Map<Uuid, SignedInDevice>
}

/**
 * Told once a device is removed (its owner's `ostrom devices revoke`, an owner or an admin of an organization the
 * device's owner is in), after its sign-ins were revoked: what ran on it ends with it (TeamCode ends the device's
 * remote sessions with `device_removed`). The identity module calls every listener bound in Koin; one that fails makes
 * the removal answer an error, the device staying removed.
 */
fun interface DeviceRemovalListener {
    /**
     * The device is gone.
     *
     * @param userId Its owner.
     * @param deviceId The device.
     */
    suspend fun removed(userId: Uuid, deviceId: Uuid)
}
