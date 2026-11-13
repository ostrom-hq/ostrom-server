// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.infrastructure

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.server.compiler.domain.repositories.WorkflowSettingsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WorkflowSettingsRepository] on PostgreSQL (sqlx4k): one row per organization, under its row level security.
 *
 * @param driver The connection pool.
 */
class PostgresWorkflowSettingsRepository(private val driver: Driver) : WorkflowSettingsRepository {
    override suspend fun editors(orgId: Uuid): WorkflowEditors? = driver.inOrg(orgId) {
        fetch(
            Statement.create("SELECT mode, member_ids FROM compiler_workflow_settings WHERE org_id = :org")
                .bind("org", orgId),
        ).firstOrNull()?.let { row ->
            WorkflowEditors(row.get("mode").asString(), Json.decodeFromString(IDS, row.get("member_ids").asString()))
        }
    }

    override suspend fun saveEditors(orgId: Uuid, editors: WorkflowEditors, by: String, at: Instant) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO compiler_workflow_settings (org_id, mode, member_ids, updated_by, updated_at) " +
                        "VALUES (:org, :mode, :ids, :by, :at) ON CONFLICT (org_id) DO UPDATE SET " +
                        "mode = EXCLUDED.mode, member_ids = EXCLUDED.member_ids, " +
                        "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                ).bind("org", orgId).bind("mode", editors.mode)
                    .bind("ids", Json.encodeToString(IDS, editors.memberIds.orEmpty()))
                    .bind("by", by).bind("at", at),
            )
        }
    }
}

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val IDS = ListSerializer(String.serializer())
