// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/**
 * The members' notification switches (TeamCode's `member_notification_settings`): one row per member, channel and
 * kind a member changed; no row means on.
 */
interface NotificationSettingsRepository {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return The member's stored switches; a missing one is on.
     */
    suspend fun settings(orgId: Uuid, memberId: Uuid): List<NotificationSetting>

    /**
     * Stores one switch of a member, replacing the one before.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param setting The switch.
     */
    suspend fun set(orgId: Uuid, memberId: Uuid, setting: NotificationSetting)

    /**
     * The router's read: of [memberIds], the switches turned off, in one statement.
     *
     * @param orgId The organization.
     * @param memberIds The members a page of the journal tells.
     *
     * @return The switches turned off.
     */
    suspend fun disabled(orgId: Uuid, memberIds: Set<Uuid>): Set<SwitchedOff>

    /**
     * Erases an organization's switches, once it is deleted.
     *
     * @param orgId The organization.
     */
    suspend fun deleteAll(orgId: Uuid)
}
