// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.rules.domain.models.AnalysisFailure
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.AnalysisTransaction
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asStringOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * [AnalysisRepository] on PostgreSQL (`rules_analyses`, one row per organization and kind), under row level security.
 *
 * @param driver The connection pool.
 */
class PostgresAnalysisRepository(private val driver: Driver) : AnalysisRepository {
    override suspend fun run(orgId: Uuid, kind: AnalysisKind): AnalysisRun? = driver.withOrganization(orgId) { tx ->
        tx.select(orgId, kind, lock = false)
    }

    override suspend fun <T> transaction(orgId: Uuid, block: suspend AnalysisTransaction.() -> T): T =
        driver.withOrganization(orgId) { tx -> PostgresAnalysisTransaction(tx, orgId).block() }

    private class PostgresAnalysisTransaction(private val tx: QueryExecutor, private val orgId: Uuid) :
        AnalysisTransaction {
        override suspend fun lock(kind: AnalysisKind): AnalysisRun? {
            // An empty row first, so two first starts queue on the same lock instead of racing on the insert.
            tx.execute(
                Statement.create(
                    "INSERT INTO rules_analyses (org_id, kind, phase, progress, items, updated_at) " +
                        "VALUES (:org, :kind, 'idle', 0, '[]', now()) ON CONFLICT (org_id, kind) DO NOTHING",
                ).bind("org", orgId).bind("kind", kind.value),
            ).getOrThrow()
            return tx.select(orgId, kind, lock = true)
        }

        override suspend fun put(run: AnalysisRun) {
            val failure = run.failure
            tx.execute(
                Statement.create(
                    "UPDATE rules_analyses SET run_id = :run, phase = :phase, progress = :progress, " +
                        "failure_stage = :stage, failure_reason = :reason, failed_at = :failed, items = :items, " +
                        "started_by = :by, started_at = :started, updated_at = :updated " +
                        "WHERE org_id = :org AND kind = :kind",
                )
                    .bind("org", orgId)
                    .bind("kind", run.kind.value)
                    .bind("run", run.id)
                    .bind("phase", run.phase)
                    .bind("progress", run.progress)
                    .bind("stage", failure?.stage)
                    .bind("reason", failure?.reason)
                    .bind("failed", failure?.at)
                    .bind("items", JsonArray(run.items).toString())
                    .bind("by", run.startedBy)
                    .bind("started", run.startedAt)
                    .bind("updated", run.updatedAt),
            ).getOrThrow()
        }
    }

    private companion object {
        const val COLUMNS = "org_id, run_id, phase, progress, failure_stage, failure_reason, failed_at, items, " +
            "started_by, started_at, updated_at"

        suspend fun QueryExecutor.select(orgId: Uuid, kind: AnalysisKind, lock: Boolean): AnalysisRun? = fetchAll(
            Statement.create(
                "SELECT $COLUMNS FROM rules_analyses WHERE org_id = :org AND kind = :kind" +
                    if (lock) " FOR UPDATE" else "",
            ).bind("org", orgId).bind("kind", kind.value),
        ).getOrThrow().firstOrNull()?.toRun(kind)

        // The empty row of a kind never started reads as no run.
        fun ResultSet.Row.toRun(kind: AnalysisKind): AnalysisRun? {
            val id = get("run_id").asStringOrNull() ?: return null
            return AnalysisRun(
                orgId = get("org_id").asUuid(),
                kind = kind,
                id = id,
                phase = get("phase").asString(),
                progress = get("progress").asDouble(),
                failure = get("failure_stage").asStringOrNull()?.let { stage ->
                    val reason = get("failure_reason").asString()
                    AnalysisFailure(stage, reason, get("failed_at").asInstant(), kind.retryPath)
                },
                startedBy = get("started_by").asString(),
                startedAt = get("started_at").asInstant(),
                updatedAt = get("updated_at").asInstant(),
                items = Json.parseToJsonElement(get("items").asString()).jsonArray.map { it.jsonObject },
            )
        }
    }
}
