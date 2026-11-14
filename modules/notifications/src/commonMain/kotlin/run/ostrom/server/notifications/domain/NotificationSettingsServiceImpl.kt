// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Notifications.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/**
 * [NotificationSettingsService] over the stored switches: every kind is on for every channel the server offers until
 * the member turns it off.
 *
 * @param repository The switches.
 * @param offered The ids of the channels this server offers, read at the first call: a channel is built only when used.
 */
class NotificationSettingsServiceImpl(
    private val repository: NotificationSettingsRepository,
    offered: () -> List<String>,
) : NotificationSettingsService {
    private val channels by lazy(offered)

    override suspend fun settings(orgId: Uuid, memberId: Uuid): MemberNotificationSettings {
        val stored = repository.settings(orgId, memberId).associateBy { it.channel to it.kind }
        val settings = channels.flatMap { channel ->
            NotificationKind.entries.map { kind -> stored[channel to kind] ?: NotificationSetting(channel, kind, true) }
        }
        return MemberNotificationSettings(channels, settings)
    }

    override suspend fun set(
        orgId: Uuid,
        memberId: Uuid,
        channel: String,
        kind: String,
        enabled: Boolean,
    ): MemberNotificationSettings {
        if (channel !in channels) {
            val shown = channel.take(MAX_CHANNEL_SHOWN)
            throw InvalidNotificationSettingException("This server has no notification channel `$shown`.")
        }
        val parsed = NotificationKind.parse(kind) ?: throw InvalidNotificationSettingException(UNKNOWN_KIND)
        repository.set(orgId, memberId, NotificationSetting(channel, parsed, enabled))
        return settings(orgId, memberId)
    }

    private companion object {
        const val UNKNOWN_KIND = "Unknown notification kind."
        const val MAX_CHANNEL_SHOWN = 16
    }
}
