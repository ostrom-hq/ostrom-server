// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A phone a member registered for push notifications.
 *
 * @property id The device.
 * @property orgId The organization.
 * @property personId The member.
 * @property pushToken The Expo push token: sent to Expo only, never returned nor logged.
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language, if it said.
 * @property createdAt When.
 */
data class PushDevice(
    val id: Uuid,
    val orgId: Uuid,
    val personId: String,
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String?,
    val createdAt: Instant,
)

/**
 * What a phone is asked to show.
 *
 * @property to The push token.
 * @property title The title.
 * @property body The text.
 * @property data What the app opens: `{kind: "approval", approvalId}`.
 */
data class PushMessage(val to: String, val title: String, val body: String, val data: Map<String, String>)

/**
 * An email to send.
 *
 * @property to The address.
 * @property subject The subject.
 * @property text The plain text body.
 */
data class EmailMessage(val to: String, val subject: String, val text: String)
