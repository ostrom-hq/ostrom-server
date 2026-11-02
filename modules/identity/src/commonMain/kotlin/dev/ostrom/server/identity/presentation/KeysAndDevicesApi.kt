// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.IssuedToken
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `listServiceKeys`, `createServiceKey`,
// `deleteServiceKey`, `listDevices` and `deleteDevice`).

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
     * `DELETE /api/v1/devices/{id}`: signs a workstation out.
     *
     * @property parent The devices resource.
     * @property id The device id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: DevicesApi = DevicesApi(), val id: String)
}

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
 * A workstation where the CLI is signed in (contract `Device`): one live CLI token.
 *
 * @property id The id, its token's.
 * @property name What the CLI called itself when it signed in.
 * @property lastSeenAt Its last call, precise to five minutes; absent before the first.
 */
@Serializable
data class DeviceResponse(val id: String, val name: String, val lastSeenAt: String? = null)

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

/** Maps a CLI token to the device that holds it. */
fun ApiToken.toDeviceResponse() = DeviceResponse(id.toString(), name, lastUsedAt?.toString())
