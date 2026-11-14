// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WaitsRepository] on PostgreSQL (table `automations_waits` of [AutomationsMigrations]). The question is JSON; the
 * first answer wins because a wait is only ever resolved or cancelled `WHERE status = 'open'`. Every query runs
 * confined to its organization ([inTenant], S2-01). Each change publishes its wait event to the organization's feed
 * (`cloud.wait.created`, `.resolved`, `.cancelled`) in its own transaction: the event exists exactly when the change
 * committed.
 *
 * @param driver The database.
 * @param events The organization's event feed.
 */
internal class WaitsRepositoryImpl(private val driver: Driver, private val events: OrgEventPublisher) : WaitsRepository {
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
                    question, created_at, idempotency_key, resolver, due_at, timeout_at, event_source, resume_message,
                    event_secret_hash) VALUES (:id, :org_id, :kind, :status, :session_id, :run_id, :assignee,
                    :created_by, :question, :created_at, :key, :resolver, :due_at, :timeout_at, :event_source,
                    :resume_message, :event_secret_hash) ON CONFLICT (org_id, created_by, idempotency_key) DO NOTHING
                    RETURNING id
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
                    .bind("key", wait.idempotencyKey)
                    .bind("resolver", wait.resolver.wire)
                    .bind("due_at", wait.timer?.dueAt)
                    .bind("timeout_at", wait.timer?.timeoutAt)
                    .bind("event_source", wait.timer?.source)
                    .bind("resume_message", wait.timer?.resumeMessage)
                    .bind("event_secret_hash", wait.timer?.secretHash),
            ).getOrThrow()
            if (inserted.none()) return@inTenant CreateOutcome.DUPLICATE
            events.publishIn(this, wait.orgId, listOf(wait.event(OrgEventType.WAIT_CREATED)))
            CreateOutcome.CREATED
        }

    override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT $WAIT_COLUMNS FROM automations_waits WHERE org_id = :org_id AND created_by = :created_by AND
            idempotency_key = :key
            """,
        ).bind("org_id", orgId).bind("created_by", createdBy).bind("key", key),
    ).getOrThrow().firstOrNull()?.toWait()

    override suspend fun get(orgId: Uuid, id: Uuid): Wait? = driver.fetchInOrg(
        orgId,
        Statement.create("""SELECT $WAIT_COLUMNS FROM automations_waits WHERE id = :id AND org_id = :org_id""")
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
            SELECT $WAIT_COLUMNS FROM automations_waits WHERE org_id = :org_id
            AND (CAST(:assignee AS TEXT) IS NULL OR assignee = :assignee)
            AND (CAST(:involving AS TEXT) IS NULL OR assignee = :involving OR created_by = :involving)
            AND (CAST(:created_by AS TEXT) IS NULL OR created_by = :created_by)
            AND (CAST(:status AS TEXT) IS NULL OR status = :status)
            AND (CAST(:session_id AS TEXT) IS NULL OR session_id = :session_id)
            AND (CAST(:session_ids AS TEXT) IS NULL
                OR session_id = ANY(string_to_array(CAST(:session_ids AS TEXT), ',')))
            AND (CAST(:run_id AS TEXT) IS NULL OR run_id = :run_id)
            AND (CAST(:resolver AS TEXT) IS NULL OR resolver = :resolver)$cursor
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
            .bind("resolver", filter.resolver?.wire)
            .bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.fetchInOrg(filter.orgId, statement).getOrThrow().map { it.toWait() }
    }

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait? = driver.changeInOrg(orgId, events, {
        it.event(OrgEventType.WAIT_RESOLVED, answer.action)
    }) {
        Statement.create(
            """
            UPDATE automations_waits SET status = 'resolved', answer_action = :action, answer_values = :values,
            answered_by = :author, answered_at = :at, resolved_at = :at, wake_pending = session_id IS NOT NULL
            WHERE id = :id AND org_id = :org_id AND status = 'open' RETURNING $WAIT_COLUMNS
            """,
        ).bind("action", answer.action)
            .bind("values", JsonObject(answer.values).toString())
            .bind("author", answer.author)
            .bind("at", answer.answeredAt)
            .bind("id", id)
            .bind("org_id", orgId)
    }.firstOrNull()

    override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? =
        driver.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_CANCELLED) }) {
            Statement.create(
                """
                UPDATE automations_waits SET status = 'cancelled', resolved_at = :now WHERE id = :id AND org_id =
                :org_id AND status = 'open' RETURNING $WAIT_COLUMNS
                """,
            ).bind("now", now).bind("id", id).bind("org_id", orgId)
        }.firstOrNull()

    override suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait> =
        driver.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_CANCELLED) }) {
            // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
            Statement.create(
                """
                UPDATE automations_waits SET status = 'cancelled', resolved_at = :now WHERE org_id = :org_id AND
                session_id = ANY(string_to_array(:session_ids, ',')) AND status = 'open' RETURNING $WAIT_COLUMNS
                """,
            ).bind("now", now).bind("org_id", orgId).bind("session_ids", sessions.joinToString(","))
        }

    override suspend fun takeAnswered(orgId: Uuid, sessions: List<String>, limit: Int): List<Wait> =
        driver.fetchInOrg(
            orgId,
            // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
            Statement.create(
                """
                UPDATE automations_waits SET wake_pending = FALSE WHERE id IN (SELECT id FROM automations_waits
                WHERE org_id = :org_id AND session_id = ANY(string_to_array(:session_ids, ',')) AND wake_pending
                ORDER BY resolved_at, id LIMIT :limit FOR UPDATE SKIP LOCKED) AND org_id = :org_id
                RETURNING $WAIT_COLUMNS
                """,
            ).bind("org_id", orgId).bind("session_ids", sessions.joinToString(",")).bind("limit", limit),
        ).getOrThrow().map { it.toWait() }.sortedWith(compareBy({ it.resolvedAt }, { it.id.toString() }))

    override suspend fun hasOpen(orgId: Uuid, sessions: List<String>): Boolean = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT EXISTS (SELECT 1 FROM automations_waits WHERE org_id = :org_id AND
            session_id = ANY(string_to_array(:session_ids, ',')) AND status = 'open') AS open
            """,
        ).bind("org_id", orgId).bind("session_ids", sessions.joinToString(",")),
    ).getOrThrow().first().get("open").asBoolean()
}
