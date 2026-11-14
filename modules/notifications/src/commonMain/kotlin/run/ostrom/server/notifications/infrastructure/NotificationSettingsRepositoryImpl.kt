// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSetting
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.SwitchedOff
import kotlin.uuid.Uuid

/**
 * [NotificationSettingsRepository] on `notifications_member_settings` (version 4 of [NotificationsMigrations]), every
 * statement confined to its organization. A stored kind this server no longer knows is left out.
 *
 * @param driver The database.
 */
class NotificationSettingsRepositoryImpl(private val driver: Driver) : NotificationSettingsRepository {
    override suspend fun settings(orgId: Uuid, memberId: Uuid): List<NotificationSetting> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(SETTINGS_SQL).bind("org_id", orgId).bind("member_id", memberId),
            ).getOrThrow().mapNotNull { row ->
                NotificationKind.parse(row.get("kind").asString())?.let {
                    NotificationSetting(row.get("channel").asString(), it, row.get("enabled").asBoolean())
                }
            }
        }

    override suspend fun set(orgId: Uuid, memberId: Uuid, setting: NotificationSetting) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(SET_SQL)
                    .bind("org_id", orgId)
                    .bind("member_id", memberId)
                    .bind("channel", setting.channel)
                    .bind("kind", setting.kind.wire)
                    .bind("enabled", setting.enabled),
            ).getOrThrow()
        }
    }

    override suspend fun disabled(orgId: Uuid, memberIds: Set<Uuid>): Set<SwitchedOff> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(DISABLED_SQL).bind("org_id", orgId).bind("members", memberIds.joinToString(",")),
            ).getOrThrow().mapNotNull { row ->
                NotificationKind.parse(row.get("kind").asString())?.let {
                    SwitchedOff(row.get("member_id").asUuid(), row.get("channel").asString(), it)
                }
            }.toSet()
        }

    override suspend fun deleteAll(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(DELETE_SQL).bind("org_id", orgId)).getOrThrow()
        }
    }

    private companion object {
        const val SETTINGS_SQL = "SELECT channel, kind, enabled FROM notifications_member_settings " +
            "WHERE org_id = :org_id AND member_id = :member_id"
        const val SET_SQL = "INSERT INTO notifications_member_settings (org_id, member_id, channel, kind, enabled) " +
            "VALUES (:org_id, :member_id, :channel, :kind, :enabled) " +
            "ON CONFLICT (org_id, member_id, channel, kind) DO UPDATE SET enabled = EXCLUDED.enabled"
        const val DISABLED_SQL = "SELECT member_id, channel, kind FROM notifications_member_settings " +
            "WHERE org_id = :org_id AND NOT enabled " +
            "AND member_id = ANY(CAST(string_to_array(CAST(:members AS TEXT), ',') AS UUID[]))"
        const val DELETE_SQL = "DELETE FROM notifications_member_settings WHERE org_id = :org_id"
    }
}
