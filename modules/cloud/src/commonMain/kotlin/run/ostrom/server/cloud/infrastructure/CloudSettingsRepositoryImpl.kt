// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsRepository
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [CloudSettingsRepository] on PostgreSQL: one row of `cloud_org_settings` per organization, in its tenant. */
class CloudSettingsRepositoryImpl(private val driver: Driver) : CloudSettingsRepository {
    override suspend fun get(orgId: Uuid): CloudSettings? = driver.inTenant(orgId) {
        fetchAll(
            Statement.create("SELECT body, updated_at, updated_by FROM cloud_org_settings WHERE org_id = :org")
                .bind("org", orgId),
        ).getOrThrow().firstOrNull()?.let { row ->
            BODY.decodeFromString(CloudSettings.serializer(), row.get("body").asString())
                .copy(updatedAt = row.get("updated_at").asInstant(), updatedBy = row.get("updated_by").asUuid())
        }
    }

    override suspend fun save(orgId: Uuid, settings: CloudSettings, expectedUpdatedAt: Instant?): Boolean =
        driver.inTenant(orgId) {
            val sql = if (expectedUpdatedAt == null) {
                "INSERT INTO cloud_org_settings (org_id, body, updated_at, updated_by) " +
                    "VALUES (:org, :body, :at, :by) ON CONFLICT (org_id) DO NOTHING"
            } else {
                "UPDATE cloud_org_settings SET body = :body, updated_at = :at, updated_by = :by " +
                    "WHERE org_id = :org AND updated_at = :expected"
            }
            val statement = Statement.create(sql)
                .bind("org", orgId)
                .bind("body", BODY.encodeToString(CloudSettings.serializer(), settings))
                .bind("at", settings.updatedAt)
                .bind("by", settings.updatedBy)
                .let { if (expectedUpdatedAt == null) it else it.bind("expected", expectedUpdatedAt) }
            execute(statement).getOrThrow() == 1L
        }
}

// Every field written: a value saved equal to today's default is not read back as a later, different default.
private val BODY = Json(OstromJson) { encodeDefaults = true }
