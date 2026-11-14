// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/**
 * The settings of a member: the channels this server offers and the switches in force, every pair with its default.
 *
 * @property channels The channels' ids, empty while the server offers none.
 * @property settings One switch per channel and kind.
 */
data class MemberNotificationSettings(val channels: List<String>, val settings: List<NotificationSetting>)

/**
 * A member's own notification switches (TeamCode's `NotificationService`). The caller has already been checked to be
 * a person signed in interactively: a service key has no person to notify.
 */
interface NotificationSettingsService {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return The member's settings.
     */
    suspend fun settings(orgId: Uuid, memberId: Uuid): MemberNotificationSettings

    /**
     * Turns one kind on or off on one channel.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param channel A channel this server offers.
     * @param kind A notification kind's wire name.
     * @param enabled Whether the member is told.
     *
     * @return The member's settings after the change.
     *
     * @throws InvalidNotificationSettingException For a channel the server does not offer or an unknown kind.
     */
    suspend fun set(
        orgId: Uuid,
        memberId: Uuid,
        channel: String,
        kind: String,
        enabled: Boolean,
    ): MemberNotificationSettings
}
