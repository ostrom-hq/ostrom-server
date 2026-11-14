// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.SecretsUnavailableException
import run.ostrom.server.automations.domain.ServedSecrets
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSecret
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.domain.SecretCipher
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
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
 * The values of secret fields (`waits.secrets.v1`) are sealed with [cipher] into `automations_wait_secrets`, in the
 * answer's own transaction; the wait's row holds only `provided`. Without a cipher no secret can be kept or served.
 *
 * @param driver The database.
 * @param events The organization's event feed.
 * @param cipher Seals the secrets at rest; null on a server without the secrets module.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the feature.
internal class WaitsRepositoryImpl(
    private val driver: Driver,
    private val events: OrgEventPublisher,
    private val cipher: SecretCipher? = null,
) : WaitsRepository {
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

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer, secrets: List<WaitSecret>): Wait? {
        if (secrets.isNotEmpty() && cipher == null) throw SecretsUnavailableException()
        return driver.inTenant(orgId) {
            val resolved = fetchAll(
                Statement.create(
                    """
                    UPDATE automations_waits SET status = 'resolved', answer_action = :action,
                    answer_values = :values, answered_by = :author, answered_at = :at, resolved_at = :at,
                    wake_pending = session_id IS NOT NULL
                    WHERE id = :id AND org_id = :org_id AND status = 'open' RETURNING $WAIT_COLUMNS
                    """,
                ).bind("action", answer.action)
                    .bind("values", JsonObject(answer.values).toString())
                    .bind("author", answer.author)
                    .bind("at", answer.answeredAt)
                    .bind("id", id)
                    .bind("org_id", orgId),
            ).getOrThrow().map { it.toWait() }
            events.publishIn(this, orgId, resolved.map { it.event(OrgEventType.WAIT_RESOLVED, answer.action) })
            // Sealed with the answer, so that an answer is never stored without the secret it says was provided.
            resolved.firstOrNull()?.also { keep(it, secrets, answer.answeredAt) }
        }
    }

    /**
     * Seals [secrets] for the wait's session. The context binds each to its organization, session and name, so a value
     * copied to another row does not open; a later answer with the same name replaces the row. The member the session
     * runs for is the wait's asker: an agent asks for its person only.
     */
    private suspend fun Transaction.keep(wait: Wait, secrets: List<WaitSecret>, at: Instant) {
        val session = wait.sessionId ?: return
        for (secret in secrets) {
            val context = secretContext(wait.orgId, session, secret.envName)
            val sealed = checkNotNull(cipher).seal(secret.value.encodeToByteArray(), context)
            execute(
                Statement.create(
                    """
                    INSERT INTO automations_wait_secrets (org_id, session_id, env_name, wait_id, field_id, owner_id,
                    one_time, value, created_at, expires_at) VALUES (:org_id, :session_id, :env_name, :wait_id,
                    :field_id, :owner_id, :one_time, :value, :created_at, :expires_at)
                    ON CONFLICT (org_id, session_id, env_name) DO UPDATE SET wait_id = EXCLUDED.wait_id,
                    field_id = EXCLUDED.field_id, owner_id = EXCLUDED.owner_id, one_time = EXCLUDED.one_time,
                    value = EXCLUDED.value, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                    """,
                ).bind("org_id", wait.orgId)
                    .bind("session_id", session)
                    .bind("env_name", secret.envName)
                    .bind("wait_id", wait.id)
                    .bind("field_id", secret.fieldId)
                    .bind("owner_id", wait.createdBy)
                    .bind("one_time", secret.oneTime)
                    .bind("value", sealed)
                    .bind("created_at", at)
                    .bind("expires_at", secret.expiresAt),
            ).getOrThrow()
        }
    }

    override suspend fun serveSecrets(orgId: Uuid, sessionId: String, ownerId: String, now: Instant): ServedSecrets {
        val sealing = cipher ?: return ServedSecrets()
        return driver.inTenant(orgId) {
            // Locked, so two replicas never serve a one-time secret twice.
            val rows = fetchAll(
                Statement.create(
                    """
                    SELECT env_name, value, one_time FROM automations_wait_secrets WHERE org_id = :org_id AND
                    session_id = :session_id AND owner_id = :owner_id AND expires_at > :now ORDER BY env_name FOR UPDATE
                    """,
                ).bind("org_id", orgId).bind("session_id", sessionId).bind("owner_id", ownerId).bind("now", now),
            ).getOrThrow().toList()
            val values = mutableMapOf<String, String>()
            val oneTime = mutableSetOf<String>()
            for (row in rows) {
                val name = row.get("env_name").asString()
                // A value that does not open (another key, another row) is never served.
                val opened = sealing.open(row.get("value").asString(), secretContext(orgId, sessionId, name))
                    ?: continue
                values[name] = opened.decodeToString()
                if (row.get("one_time").asBoolean()) oneTime += name
            }
            if (oneTime.isNotEmpty()) {
                execute(
                    Statement.create(
                        """
                        DELETE FROM automations_wait_secrets WHERE org_id = :org_id AND session_id = :session_id AND
                        env_name = ANY(string_to_array(:names, ','))
                        """,
                    ).bind("org_id", orgId).bind("session_id", sessionId).bind("names", oneTime.joinToString(",")),
                ).getOrThrow()
            }
            ServedSecrets(values, oneTime)
        }
    }

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

/** What a secret is sealed with: its organization, session and variable, so that a copied value does not open. */
private fun secretContext(orgId: Uuid, sessionId: String, envName: String) = "wait-secret:$orgId:$sessionId:$envName"
