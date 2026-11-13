// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.admin.domain.InstanceSettingsRepository
import run.ostrom.server.admin.domain.StoredSettings
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** [InstanceSettingsRepository] on PostgreSQL (`admin_settings`, the instance's, no organization). */
class InstanceSettingsRepositoryImpl(private val driver: Driver) : InstanceSettingsRepository {
    override suspend fun get(section: String): StoredSettings? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM admin_settings WHERE section = :section").bind("section", section),
    ).getOrThrow().firstOrNull()?.toSettings()

    override suspend fun all(): Map<String, StoredSettings> =
        driver.fetchAll("SELECT $COLUMNS FROM admin_settings").getOrThrow().map { it.toSettings() }
            .associateBy { it.section }

    override suspend fun save(settings: StoredSettings): Boolean = driver.execute(
        bind(
            Statement.create(
                "UPDATE admin_settings SET plain_values = :plain, sealed_values = :sealed, version = :version, " +
                    "updated_at = :at, updated_by = :by WHERE section = :section AND version = :previous",
            ),
            settings,
        ).bind("previous", settings.version - 1),
    ).getOrThrow() == 1L

    override suspend fun insertIfAbsent(settings: StoredSettings): Boolean = driver.execute(
        bind(
            Statement.create(
                "INSERT INTO admin_settings (section, plain_values, sealed_values, version, updated_at, updated_by) " +
                    "VALUES (:section, :plain, :sealed, :version, :at, :by) ON CONFLICT (section) DO NOTHING",
            ),
            settings,
        ),
    ).getOrThrow() == 1L

    private fun bind(statement: Statement, settings: StoredSettings): Statement = statement
        .bind("section", settings.section)
        .bind("plain", OstromJson.encodeToString(MAP, settings.values))
        .bind("sealed", OstromJson.encodeToString(MAP, settings.sealed))
        .bind("version", settings.version)
        .bind("at", settings.updatedAt)
        .bind("by", settings.updatedBy)

    private fun ResultSet.Row.toSettings() = StoredSettings(
        section = get("section").asString(),
        values = OstromJson.decodeFromString(MAP, get("plain_values").asString()),
        sealed = OstromJson.decodeFromString(MAP, get("sealed_values").asString()),
        version = get("version").asInt(),
        updatedAt = get("updated_at").asInstant(),
        updatedBy = get("updated_by").asUuidOrNull(),
    )

    private companion object {
        const val COLUMNS = "section, plain_values, sealed_values, version, updated_at, updated_by"
        val MAP = MapSerializer(String.serializer(), String.serializer())
    }
}
