// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A phone a member registered for push notifications in their organization: one per push token, so a phone signed in
 * again by another member of the organization is theirs from then on.
 *
 * @property id The device.
 * @property orgId The organization.
 * @property memberId The member the phone notifies.
 * @property pushToken The Expo push token, never answered back.
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language, if it gave one.
 * @property createdAt When the phone was first registered.
 * @property seenAt When the phone last registered (the app registers again in a new language, or an hour later).
 */
data class PushDevice(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String?,
    val createdAt: Instant,
    val seenAt: Instant,
)

/**
 * A phone, as the app registers it (`registerPushDevice`).
 *
 * @property pushToken The Expo push token (`ExponentPushToken[…]`).
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language, if any.
 */
data class PushRegistration(val pushToken: String, val provider: String, val platform: String, val locale: String?)
