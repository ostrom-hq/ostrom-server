// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/push-devices` (contract `registerPushDevice`): the caller's phones in their organization. */
@Serializable
@Resource("/api/v1/push-devices")
class PushDevicesApi {
    /**
     * One phone (contract `deletePushDevice`).
     *
     * @property parent The phones.
     * @property id The device.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: PushDevicesApi = PushDevicesApi(), val id: String)
}

/**
 * Body of `registerPushDevice`: a phone, as the app registers it.
 *
 * @property pushToken The Expo push token.
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language.
 */
@Serializable
data class PushDeviceRequest(
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
)

/**
 * A registered phone (contract `PushDevice`): never its push token.
 *
 * @property id The device.
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language, absent when it gave none.
 * @property createdAt When it was first registered, ISO-8601.
 */
@Serializable
data class PushDeviceDto(
    val id: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
    val createdAt: String,
)
