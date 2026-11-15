// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSetting
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.SwitchedOff
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [NotificationSettingsRepository] on `notifications_member_settings` (version 4 of [NotificationsMigrations]) through
 * Exposed, every statement confined to its organization (`withOrganization`, with its own `org_id` filter). A stored
 * kind this server no longer knows is left out.
 *
 * @param database The serving role's pool.
 */
class NotificationSettingsRepositoryImpl(private val database: JdbcDatabase) : NotificationSettingsRepository {
    override suspend fun settings(orgId: Uuid, memberId: Uuid): List<NotificationSetting> =
        database.withOrganization(orgId) {
            val s = NotificationsMemberSettings
            s.select(s.channel, s.kind, s.enabled)
                .where { (s.orgId eq orgId) and (s.memberId eq memberId) }
                .mapNotNull { row ->
                    val kind = NotificationKind.parse(row[s.kind])
                    if (kind == null) null else NotificationSetting(row[s.channel], kind, row[s.enabled])
                }
        }

    override suspend fun set(orgId: Uuid, memberId: Uuid, setting: NotificationSetting) {
        database.withOrganization(orgId) {
            val s = NotificationsMemberSettings
            s.upsert(s.orgId, s.memberId, s.channel, s.kind, onUpdate = { it[s.enabled] = insertValue(s.enabled) }) {
                it[s.orgId] = orgId
                it[s.memberId] = memberId
                it[s.channel] = setting.channel
                it[s.kind] = setting.kind.wire
                it[s.enabled] = setting.enabled
            }
        }
    }

    override suspend fun disabled(orgId: Uuid, memberIds: Set<Uuid>): Set<SwitchedOff> =
        database.withOrganization(orgId) {
            val s = NotificationsMemberSettings
            val members = memberIds.toList()
            s.select(s.memberId, s.channel, s.kind)
                // One array parameter, however many members the fan-out names.
                .where { (s.orgId eq orgId) and not(s.enabled) and (s.memberId eq anyFrom(members, UuidColumnType())) }
                .mapNotNull { row ->
                    val kind = NotificationKind.parse(row[s.kind])
                    if (kind == null) null else SwitchedOff(row[s.memberId], row[s.channel], kind)
                }.toSet()
        }

    override suspend fun deleteAll(orgId: Uuid) {
        database.withOrganization(orgId) {
            NotificationsMemberSettings.deleteWhere { NotificationsMemberSettings.orgId eq orgId }
        }
    }
}
