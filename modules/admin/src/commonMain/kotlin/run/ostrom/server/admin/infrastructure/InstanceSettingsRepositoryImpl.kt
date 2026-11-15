// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.admin.domain.InstanceSettingsRepository
import run.ostrom.server.admin.domain.StoredSettings
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * [InstanceSettingsRepository] on PostgreSQL (`admin_settings`, the instance's, no organization).
 *
 * @param database The serving role's pool.
 */
class InstanceSettingsRepositoryImpl(private val database: JdbcDatabase) : InstanceSettingsRepository {
    override suspend fun get(section: String): StoredSettings? = database.query {
        AdminSettings.select(AdminSettings.columns).where { AdminSettings.section eq section }
            .singleOrNull()?.toSettings()
    }

    override suspend fun all(): Map<String, StoredSettings> = database.query {
        AdminSettings.select(AdminSettings.columns)
            .orderBy(AdminSettings.section to SortOrder.ASC)
            .limit(MAX_SECTIONS)
            .map { it.toSettings() }
            .associateBy { it.section }
    }

    override suspend fun save(settings: StoredSettings): Boolean = database.query {
        // The version read before the change: a concurrent save of the same section writes nothing.
        AdminSettings.update({
            (AdminSettings.section eq settings.section) and (AdminSettings.version eq settings.version - 1)
        }) { it.values(settings) } == 1
    }

    override suspend fun insertIfAbsent(settings: StoredSettings): Boolean = database.query {
        AdminSettings.insertIgnore {
            it[section] = settings.section
            it.values(settings)
        }.insertedCount == 1
    }

    private fun UpdateBuilder<*>.values(settings: StoredSettings) {
        this[AdminSettings.plainValues] = OstromJson.encodeToString(MAP, settings.values)
        this[AdminSettings.sealedValues] = OstromJson.encodeToString(MAP, settings.sealed)
        this[AdminSettings.version] = settings.version
        this[AdminSettings.updatedAt] = settings.updatedAt
        this[AdminSettings.updatedBy] = settings.updatedBy
    }

    private fun ResultRow.toSettings() = StoredSettings(
        section = this[AdminSettings.section],
        values = OstromJson.decodeFromString(MAP, this[AdminSettings.plainValues]),
        sealed = OstromJson.decodeFromString(MAP, this[AdminSettings.sealedValues]),
        version = this[AdminSettings.version],
        updatedAt = this[AdminSettings.updatedAt],
        updatedBy = this[AdminSettings.updatedBy],
    )
}

/** The sections are the server's own (`SettingsSection`), a few dozen: the bound of a read of all, far above. */
private const val MAX_SECTIONS = 200
private val MAP = MapSerializer(String.serializer(), String.serializer())
