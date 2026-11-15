// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.rules.domain.models.AnalysisFailure
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import run.ostrom.server.rules.domain.repositories.AnalysisTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * [AnalysisRepository] on PostgreSQL (`rules_analyses`, one row per organization and kind), under row level security:
 * the serving role with the organization set (`withOrganization`), each query filtered on `org_id` too.
 *
 * @param database The serving role's pool.
 */
class PostgresAnalysisRepository(private val database: JdbcDatabase) : AnalysisRepository {
    override suspend fun run(orgId: Uuid, kind: AnalysisKind): AnalysisRun? = database.withOrganization(orgId) {
        select(orgId, kind, lock = false)
    }

    override suspend fun <T> transaction(orgId: Uuid, block: AnalysisTransaction.() -> T): T =
        database.withOrganization(orgId) { PostgresAnalysisTransaction(this, orgId).block() }

    private class PostgresAnalysisTransaction(private val tx: JdbcTransaction, private val org: Uuid) :
        AnalysisTransaction {
        override fun lock(kind: AnalysisKind): AnalysisRun? {
            // An empty row first, so two first starts queue on the same lock instead of racing on the insert.
            RulesAnalyses.insertIgnore {
                it[orgId] = org
                it[this.kind] = kind.value
                it[phase] = "idle"
                it[progress] = 0.0
                it[items] = "[]"
                it[updatedAt] = databaseNow()
            }
            return tx.select(org, kind, lock = true)
        }

        override fun put(run: AnalysisRun) {
            val failure = run.failure
            val a = RulesAnalyses
            a.update({ (a.orgId eq org) and (a.kind eq run.kind.value) }) {
                it[runId] = run.id
                it[phase] = run.phase
                it[progress] = run.progress
                it[failureStage] = failure?.stage
                it[failureReason] = failure?.reason
                it[failedAt] = failure?.at
                // One writer for the whole array: `toString` of a JSON tree copies each level into its parent.
                it[items] = Json.encodeToString(JsonArray.serializer(), JsonArray(run.items))
                it[startedBy] = run.startedBy
                it[startedAt] = run.startedAt
                it[updatedAt] = run.updatedAt
            }
        }
    }

    private companion object {
        fun JdbcTransaction.select(orgId: Uuid, kind: AnalysisKind, lock: Boolean): AnalysisRun? {
            val a = RulesAnalyses
            val query = a.select(a.columns).where { (a.orgId eq orgId) and (a.kind eq kind.value) }
            return (if (lock) query.forUpdate() else query).singleOrNull()?.toRun(kind)
        }

        // The empty row of a kind never started reads as no run.
        fun ResultRow.toRun(kind: AnalysisKind): AnalysisRun? {
            val a = RulesAnalyses
            val id = this[a.runId] ?: return null
            return AnalysisRun(
                orgId = this[a.orgId],
                kind = kind,
                id = id,
                phase = this[a.phase],
                progress = this[a.progress],
                failure = this[a.failureStage]?.let { stage ->
                    val reason = present(a.failureReason, a.failureStage)
                    AnalysisFailure(stage, reason, present(a.failedAt, a.failureStage), kind.retryPath)
                },
                startedBy = present(a.startedBy, a.runId),
                startedAt = present(a.startedAt, a.runId),
                updatedAt = this[a.updatedAt],
                items = Json.parseToJsonElement(this[a.items]).jsonArray.map { it.jsonObject },
            )
        }
    }
}
