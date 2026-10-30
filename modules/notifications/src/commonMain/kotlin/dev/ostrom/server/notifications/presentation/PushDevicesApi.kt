// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/push-devices` (contract `registerPushDevice`): the caller's phones. */
@Serializable
@Resource("/api/v1/push-devices")
class PushDevicesApi {
    /**
     * One phone (contract `deletePushDevice`).
     *
     * @property id The device.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: PushDevicesApi = PushDevicesApi(), val id: String)
}

/**
 * A phone, as the app registers it.
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
 * A registered phone (contract `PushDevice`): never its token.
 *
 * @property id The device.
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language.
 * @property createdAt When, ISO-8601.
 */
@Serializable
data class PushDeviceDto(
    val id: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
    val createdAt: String,
)
