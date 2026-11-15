// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.server.compiler.domain.repositories.WorkflowSettingsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WorkflowSettingsRepository] on PostgreSQL through Exposed: one row per organization, under its row level security
 * (`withOrganization`), read through the primary key.
 *
 * @param database The serving role's pool.
 */
class PostgresWorkflowSettingsRepository(private val database: JdbcDatabase) : WorkflowSettingsRepository {
    override suspend fun editors(orgId: Uuid): WorkflowEditors? = database.withOrganization(orgId) {
        val s = CompilerWorkflowSettings
        val row = s.select(s.mode, s.memberIds).where { s.orgId eq orgId }.firstOrNull()
        if (row == null) null else WorkflowEditors(row[s.mode], Json.decodeFromString(IDS, row[s.memberIds]))
    }

    override suspend fun saveEditors(orgId: Uuid, editors: WorkflowEditors, by: String, at: Instant) {
        database.withOrganization(orgId) {
            val s = CompilerWorkflowSettings
            // On conflict, every column but the key takes the new value (Exposed's default update).
            s.upsert(s.orgId) {
                it[s.orgId] = orgId
                it[s.mode] = editors.mode
                it[s.memberIds] = Json.encodeToString(IDS, editors.memberIds.orEmpty())
                it[s.updatedBy] = by
                it[s.updatedAt] = at
            }
        }
    }
}

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val IDS = ListSerializer(String.serializer())
