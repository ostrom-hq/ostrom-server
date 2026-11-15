// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.SignedInDevice
import run.ostrom.server.identity.domain.IssuedToken
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `listServiceKeys`, `createServiceKey`,
// `deleteServiceKey`, `listDevices`, `renameDevice`, `deleteDevice` and `listMemberDevices`).

/** `GET`/`POST /api/v1/service-keys`: the service keys of the organization the request acts in. */
@Serializable
@Resource("/api/v1/service-keys")
class ServiceKeysApi {
    /**
     * `DELETE /api/v1/service-keys/{id}`: deletes a key.
     *
     * @property parent The keys resource.
     * @property id The key id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: ServiceKeysApi = ServiceKeysApi(), val id: String)
}

/** `GET /api/v1/devices`: the workstations where the caller is signed in. */
@Serializable
@Resource("/api/v1/devices")
class DevicesApi {
    /**
     * `PATCH /api/v1/devices/{id}` renames a device, `DELETE /api/v1/devices/{id}` signs a workstation out.
     *
     * @property parent The devices resource.
     * @property id The device id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: DevicesApi = DevicesApi(), val id: String)
}

/**
 * `GET /api/v1/members/{memberId}/devices`: a member's devices, for an owner or an admin.
 *
 * @property memberId The member.
 */
@Serializable
@Resource("/api/v1/members/{memberId}/devices")
class MemberDevicesApi(val memberId: String)

/**
 * A service key to create (contract `createServiceKey`).
 *
 * @property name 1 to 100 characters.
 * @property scopes `resource:action`, 1 to 32, as for a personal token.
 * @property expiresInDays 1 to 366; no expiry when absent.
 */
@Serializable
data class CreateServiceKeyRequest(val name: String, val scopes: List<String>, val expiresInDays: Int? = null)

/**
 * A service key (contract `ServiceKey`).
 *
 * @property id The id.
 * @property name Its label.
 * @property scopes What it allows, sorted.
 * @property createdAt When it was created (ISO 8601).
 * @property expiresAt When it stops working; absent for a key that does not expire.
 * @property lastUsedAt Its last use, precise to five minutes; absent before the first.
 * @property secret Its secret: only in the answer that creates it.
 */
@Serializable
data class ServiceKeyResponse(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val createdAt: String,
    val expiresAt: String? = null,
    val lastUsedAt: String? = null,
    val secret: String? = null,
)

/**
 * The organization's live service keys.
 *
 * @property items The keys, newest first, without their secrets.
 */
@Serializable
data class ServiceKeysResponse(val items: List<ServiceKeyResponse>)

/**
 * A workstation where the CLI is signed in (contract `Device`): a registered installation (`devices.v1`), one live CLI
 * token from before devices, or one live token of `ostrom login --admin` in the organization the request names.
 *
 * @property id The id, the device's, or its token's for a token listed on its own.
 * @property name What the CLI called itself when it signed in, or what its owner renamed it.
 * @property platform The system reported at the latest sign-in; absent until one did.
 * @property osVersion The system's version.
 * @property createdAt When the installation was registered; absent for a token listed on its own.
 * @property lastLoginAt Its latest sign-in.
 * @property activeSessions How many live sign-ins the device holds.
 * @property current Whether the call came from it.
 * @property lastSeenAt Its last call, precise to five minutes; absent before the first.
 * @property kind `cli`, or `admin` for the token of `ostrom login --admin`.
 * @property orgId The organization an `admin` token is bound to; absent for `cli`.
 * @property scopes What an `admin` token may do, sorted; absent for `cli`.
 * @property expiresAt When an `admin` token stops working; absent for `cli`.
 * @property daemon The device's daemon (`runners.cloud.remote.daemon.v1`); absent when it never polled or said it
 *   stopped.
 */
@Serializable
data class DeviceResponse(
    val id: String,
    val name: String,
    val lastSeenAt: String? = null,
    val kind: String = DEVICE_KIND_CLI,
    val orgId: String? = null,
    val scopes: List<String>? = null,
    val expiresAt: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
    val createdAt: String? = null,
    val lastLoginAt: String? = null,
    val activeSessions: Int? = null,
    val current: Boolean? = null,
    val daemon: DaemonPresenceResponse? = null,
)

/**
 * A device's daemon as the server last saw it (contract `DaemonPresence`).
 *
 * @property online It polled less than a minute ago.
 * @property lastSeenAt Its latest recorded poll.
 * @property version The CLI's version it announced.
 * @property features What it announced at that poll.
 */
@Serializable
data class DaemonPresenceResponse(
    val online: Boolean,
    val lastSeenAt: String,
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/**
 * A device's new name (contract `RenameDeviceRequest`).
 *
 * @property name 1 to 120 characters, no control character.
 */
@Serializable
data class RenameDeviceRequest(val name: String)

/**
 * The caller's workstations.
 *
 * @property items The devices, newest first.
 */
@Serializable
data class DevicesResponse(val items: List<DeviceResponse>)

/** Maps a service key, without its secret. */
fun ApiToken.toServiceKeyResponse() = ServiceKeyResponse(
    id = id.toString(),
    name = name,
    scopes = scopes.sorted(),
    createdAt = createdAt.toString(),
    expiresAt = expiresAt?.toString(),
    lastUsedAt = lastUsedAt?.toString(),
)

/** Maps a service key just created, with its secret. */
fun IssuedToken.toCreatedServiceKeyResponse() = token.toServiceKeyResponse().copy(secret = secret)

/** Maps a line of the listing: a registered device, a CLI token on its own, or a token of `ostrom login --admin`. */
fun SignedInDevice.toDeviceResponse(): DeviceResponse {
    val device = DeviceResponse(
        id = id.toString(),
        name = name,
        lastSeenAt = lastSeenAt?.toString(),
        platform = platform,
        osVersion = osVersion,
        createdAt = createdAt?.toString(),
        lastLoginAt = lastLoginAt?.toString(),
        activeSessions = activeSessions,
        current = current,
        daemon = daemon?.let { DaemonPresenceResponse(it.online, it.lastSeenAt.toString(), it.version, it.features) },
    )
    val token = elevated ?: return device
    return device.copy(
        kind = DEVICE_KIND_ADMIN,
        orgId = token.orgId?.toString(),
        scopes = token.scopes.sorted(),
        expiresAt = token.expiresAt?.toString(),
    )
}

/** The contract's `Device.kind` of a CLI token. */
const val DEVICE_KIND_CLI: String = "cli"

/** The contract's `Device.kind` of a token of `ostrom login --admin`. */
const val DEVICE_KIND_ADMIN: String = "admin"
