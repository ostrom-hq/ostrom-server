// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WaitsRepository] on PostgreSQL (table `automations_waits` of [AutomationsMigrations]). The question is JSON; the
 * first answer wins because a wait is only ever resolved or cancelled `WHERE status = 'open'`. Every query runs
 * confined to its organization ([inTenant], S2-01).
 *
 * @param driver The database.
 */
internal class WaitsRepositoryImpl(private val driver: Driver) : WaitsRepository {
    override suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String>): CreateOutcome =
        driver.inTenant(wait.orgId) {
            // Serialize the count and the insert of one scope, so that two concurrent asks never both pass the limit.
            // Keyed on the session written to: an agent asks only in its launch's open segment.
            val session = wait.sessionId
            val scope = if (session == null) "member:${wait.createdBy}" else "session:$session"
            fetchAll(
                Statement.create("""SELECT pg_advisory_xact_lock(hashtext(:key))""")
                    .bind("key", "automations:${wait.orgId}:$scope"),
            ).getOrThrow()
            val count = if (session == null) {
                Statement.create(
                    """
                    SELECT COUNT(*) AS n FROM automations_waits WHERE org_id = :org_id AND created_by = :created_by AND
                    session_id IS NULL AND status = 'open'
                    """,
                ).bind("created_by", wait.createdBy)
            } else {
                // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
                Statement.create(
                    """
                    SELECT COUNT(*) AS n FROM automations_waits WHERE org_id = :org_id AND status = 'open' AND
                    session_id = ANY(string_to_array(:session_ids, ','))
                    """,
                ).bind("session_ids", (sessions + session).distinct().joinToString(","))
            }
            val open = fetchAll(count.bind("org_id", wait.orgId)).getOrThrow().first().get("n").asInt()
            if (open >= maxOpen) return@inTenant CreateOutcome.AT_LIMIT
            val inserted = fetchAll(
                Statement.create(
                    """
                    INSERT INTO automations_waits (id, org_id, kind, status, session_id, run_id, assignee, created_by,
                    question, created_at, idempotency_key) VALUES (:id, :org_id, :kind, :status, :session_id, :run_id,
                    :assignee, :created_by, :question, :created_at, :key) ON CONFLICT (org_id, created_by,
                    idempotency_key) DO NOTHING RETURNING id
                    """,
                ).bind("id", wait.id)
                    .bind("org_id", wait.orgId)
                    .bind("kind", wait.kind)
                    .bind("status", wait.status.wire)
                    .bind("session_id", wait.sessionId)
                    .bind("run_id", wait.runId)
                    .bind("assignee", wait.assignee)
                    .bind("created_by", wait.createdBy)
                    .bind("question", OstromJson.encodeToString(WaitSpec.serializer(), wait.question))
                    .bind("created_at", wait.createdAt)
                    .bind("key", wait.idempotencyKey),
            ).getOrThrow()
            if (inserted.none()) CreateOutcome.DUPLICATE else CreateOutcome.CREATED
        }

    override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT $COLUMNS FROM automations_waits WHERE org_id = :org_id AND created_by = :created_by AND
            idempotency_key = :key
            """,
        ).bind("org_id", orgId).bind("created_by", createdBy).bind("key", key),
    ).getOrThrow().firstOrNull()?.toWait()

    override suspend fun get(orgId: Uuid, id: Uuid): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create("""SELECT $COLUMNS FROM automations_waits WHERE id = :id AND org_id = :org_id""")
            .bind("id", id).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toWait()

    override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> {
        // The cursor is written into the SQL only when set: a NULL of no declared type is never sent.
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (created_at, id) <
                (SELECT created_at, id FROM automations_waits WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM automations_waits WHERE org_id = :org_id
            AND (CAST(:assignee AS TEXT) IS NULL OR assignee = :assignee)
            AND (CAST(:involving AS TEXT) IS NULL OR assignee = :involving OR created_by = :involving)
            AND (CAST(:created_by AS TEXT) IS NULL OR created_by = :created_by)
            AND (CAST(:status AS TEXT) IS NULL OR status = :status)
            AND (CAST(:session_id AS TEXT) IS NULL OR session_id = :session_id)
            AND (CAST(:session_ids AS TEXT) IS NULL
                OR session_id = ANY(string_to_array(CAST(:session_ids AS TEXT), ',')))
            AND (CAST(:run_id AS TEXT) IS NULL OR run_id = :run_id)$cursor
            ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", filter.orgId)
            .bind("assignee", filter.assignee)
            .bind("involving", filter.involving)
            .bind("created_by", filter.createdBy)
            .bind("status", filter.status?.wire)
            .bind("session_id", filter.sessionId)
            // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
            .bind("session_ids", filter.sessionIds?.joinToString(","))
            .bind("run_id", filter.runId)
            .bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.fetchInOrg(filter.orgId, statement).getOrThrow().map { it.toWait() }
    }

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            UPDATE automations_waits SET status = 'resolved', answer_action = :action, answer_values = :values,
            answered_by = :author, answered_at = :at, resolved_at = :at WHERE id = :id AND org_id = :org_id AND
            status = 'open' RETURNING $COLUMNS
            """,
        ).bind("action", answer.action)
            .bind("values", JsonObject(answer.values).toString())
            .bind("author", answer.author)
            .bind("at", answer.answeredAt)
            .bind("id", id)
            .bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toWait()

    override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            UPDATE automations_waits SET status = 'cancelled', resolved_at = :now WHERE id = :id AND org_id =
            :org_id AND status = 'open' RETURNING $COLUMNS
            """,
        ).bind("now", now).bind("id", id).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toWait()

    override suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait> =
        driver.fetchInOrg(
            orgId,
            // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
            Statement.create(
                """
                UPDATE automations_waits SET status = 'cancelled', resolved_at = :now WHERE org_id = :org_id AND
                session_id = ANY(string_to_array(:session_ids, ',')) AND status = 'open' RETURNING $COLUMNS
                """,
            ).bind("now", now).bind("org_id", orgId).bind("session_ids", sessions.joinToString(",")),
        ).getOrThrow().map { it.toWait() }

    private fun ResultSet.Row.toWait(): Wait = Wait(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        kind = get("kind").asString(),
        status = WaitStatus.valueOf(get("status").asString().uppercase()),
        sessionId = get("session_id").asStringOrNull(),
        runId = get("run_id").asStringOrNull(),
        assignee = get("assignee").asString(),
        createdBy = get("created_by").asString(),
        question = OstromJson.decodeFromString(WaitSpec.serializer(), get("question").asString()),
        answer = get("answer_action").asStringOrNull()?.let { action ->
            WaitAnswer(
                action = action,
                values = OstromJson.parseToJsonElement(get("answer_values").asString()).jsonObject,
                author = get("answered_by").asString(),
                answeredAt = get("answered_at").asInstant(),
            )
        },
        createdAt = get("created_at").asInstant(),
        resolvedAt = get("resolved_at").asInstantOrNull(),
        idempotencyKey = get("idempotency_key").asStringOrNull(),
    )

    private companion object {
        const val COLUMNS = """
            id, org_id, kind, status, session_id, run_id, assignee, created_by, question, answer_action,
            answer_values, answered_by, answered_at, created_at, resolved_at, idempotency_key
        """
    }
}
