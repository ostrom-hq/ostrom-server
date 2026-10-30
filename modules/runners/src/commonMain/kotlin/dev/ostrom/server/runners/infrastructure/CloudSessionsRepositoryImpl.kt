package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.RepositorySpec
import dev.ostrom.server.runners.domain.ResumeOutcome
import dev.ostrom.server.runners.domain.SessionFailure
import dev.ostrom.server.runners.domain.SessionLimits
import dev.ostrom.server.runners.domain.SessionState
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudSessionsRepository] on PostgreSQL (tables of [RunnersMigrations]).
 *
 * The running cap is serialized per organization with a transaction-scoped advisory lock, so the count and the
 * write of two concurrent creations or resumes never interleave.
 *
 * @param driver The database.
 */
class CloudSessionsRepositoryImpl(private val driver: Driver) : CloudSessionsRepository {
    override suspend fun createWithinCap(session: CloudSession, maxRunning: Int): Boolean = driver.transaction {
        lockOrg(session.orgId)
        if (countHolding(session.orgId, null) >= maxRunning) return@transaction false
        execute(
            Statement.create(
                "INSERT INTO runners_sessions ($COLUMNS, next_reconcile_at) VALUES ($VALUES, :created_at)",
            ).bindInsert(session),
        ).getOrThrow()
        true
    }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM runners_sessions WHERE id = :id AND org_id = :org_id")
            .bind("id", id).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.toSession()

    override suspend fun getById(id: Uuid): CloudSession? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM runners_sessions WHERE id = :id").bind("id", id),
    ).getOrThrow().firstOrNull()?.toSession()

    override suspend fun list(orgId: Uuid, ownerId: String, before: Uuid?, limit: Int): List<CloudSession> {
        val cursor = if (before == null) {
            ""
        } else {
            " AND (created_at, id) < (SELECT created_at, id FROM runners_sessions WHERE id = :before AND org_id = :org_id)"
        }
        val statement = Statement.create(
            "SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND owner_id = :owner_id$cursor " +
                "ORDER BY created_at DESC, id DESC LIMIT :limit",
        ).bind("org_id", orgId).bind("owner_id", ownerId).bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.fetchAll(statement).getOrThrow().map { it.toSession() }
    }

    override suspend fun setDesiredState(orgId: Uuid, id: Uuid, desiredState: DesiredState, now: Instant): CloudSession? =
        driver.fetchAll(
            Statement.create(
                "UPDATE runners_sessions SET desired_state = :desired, generation = generation + 1, updated_at = :now, " +
                    "next_reconcile_at = :now WHERE id = :id AND org_id = :org_id AND state NOT IN ('terminated', 'failed') " +
                    "RETURNING $COLUMNS",
            ).bind("desired", desiredState.wire).bind("now", now).bind("id", id).bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toSession() ?: get(orgId, id)

    override suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: Instant): ResumeOutcome =
        driver.transaction {
            lockOrg(orgId)
            val current = fetchAll(
                Statement.create("SELECT $COLUMNS FROM runners_sessions WHERE id = :id AND org_id = :org_id")
                    .bind("id", id).bind("org_id", orgId),
            ).getOrThrow().firstOrNull()?.toSession() ?: return@transaction ResumeOutcome.NotFound
            if (!current.state.holdsExecution && countHolding(orgId, id) >= maxRunning) return@transaction ResumeOutcome.AtCap
            val updated = fetchAll(
                Statement.create(
                    "UPDATE runners_sessions SET desired_state = 'running', generation = generation + 1, updated_at = :now, " +
                        "next_reconcile_at = :now WHERE id = :id AND org_id = :org_id RETURNING $COLUMNS",
                ).bind("now", now).bind("id", id).bind("org_id", orgId),
            ).getOrThrow().first().toSession()
            ResumeOutcome.Resumed(updated)
        }

    override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? = driver.fetchAll(
        Statement.create(
            "UPDATE runners_sessions SET state = :state, desired_state = :desired_state, phase = :phase, epoch = :epoch, " +
                "generation = generation + 1, failure_code = :failure_code, failure_message = :failure_message, " +
                "updated_at = :updated_at, execution_started_at = :execution_started_at, ready_at = :ready_at, " +
                "last_heartbeat_at = :last_heartbeat_at, " +
                "last_activity_at = GREATEST(last_activity_at, :last_activity_at), paused_at = :paused_at, " +
                "terminated_at = :terminated_at, pausing_since = :pausing_since, runner_stopped_at = :runner_stopped_at, " +
                "runner_token_hash = :runner_token_hash, next_reconcile_at = CASE WHEN CAST(:has_next AS BOOLEAN) THEN " +
                "LEAST(COALESCE(next_reconcile_at, :next_at), :next_at) ELSE next_reconcile_at END " +
                "WHERE id = :id AND generation = :generation RETURNING $COLUMNS",
        ).bindUpdate(session)
            .bind("generation", session.generation)
            .bind("has_next", nextReconcileAt != null)
            .bind("next_at", nextReconcileAt ?: session.updatedAt),
    ).getOrThrow().firstOrNull()?.toSession()

    override suspend fun touchActivity(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create(
                "UPDATE runners_sessions SET last_activity_at = GREATEST(COALESCE(last_activity_at, :at), :at) WHERE id = :id",
            ).bind("at", at).bind("id", id),
        ).getOrThrow()
    }

    override suspend fun findByRunnerTokenHash(tokenHash: String): CloudSession? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM runners_sessions WHERE runner_token_hash = :hash").bind("hash", tokenHash),
    ).getOrThrow().firstOrNull()?.toSession()

    override suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession> =
        driver.fetchAll(
            Statement.create(
                "UPDATE runners_sessions SET reconcile_owner = :owner, reconcile_until = :until, claimed_at = :now WHERE id IN (" +
                    "SELECT id FROM runners_sessions WHERE next_reconcile_at <= :now " +
                    "AND (reconcile_until IS NULL OR reconcile_until < :now) " +
                    "ORDER BY next_reconcile_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING $COLUMNS",
            ).bind("owner", owner).bind("until", leaseUntil).bind("now", now).bind("limit", limit),
        ).getOrThrow().map { it.toSession() }

    override suspend fun release(id: Uuid, owner: String, nextReconcileAt: Instant?) {
        // An API write or a heartbeat during the pass moves next_reconcile_at after claimed_at: that request for a
        // new look is kept (the earliest of the two wins), never erased by the end of the pass.
        driver.execute(
            Statement.create(
                "UPDATE runners_sessions SET reconcile_owner = NULL, reconcile_until = NULL, next_reconcile_at = CASE " +
                    "WHEN next_reconcile_at > claimed_at AND CAST(:has_next AS BOOLEAN) THEN LEAST(next_reconcile_at, :next_at) " +
                    "WHEN next_reconcile_at > claimed_at THEN next_reconcile_at " +
                    "WHEN CAST(:has_next AS BOOLEAN) THEN :next_at ELSE NULL END " +
                    "WHERE id = :id AND reconcile_owner = :owner",
            ).bind("has_next", nextReconcileAt != null)
                .bind("next_at", nextReconcileAt ?: EPOCH)
                .bind("id", id).bind("owner", owner),
        ).getOrThrow()
    }

    override suspend fun usageSeconds(orgId: Uuid, month: String): Long = driver.fetchAll(
        Statement.create("SELECT seconds FROM runners_usage WHERE org_id = :org_id AND month = :month")
            .bind("org_id", orgId).bind("month", month),
    ).getOrThrow().firstOrNull()?.get("seconds")?.asLong() ?: 0

    override suspend fun addUsage(orgId: Uuid, month: String, seconds: Long) {
        driver.execute(
            Statement.create(
                "INSERT INTO runners_usage (org_id, month, seconds) VALUES (:org_id, :month, :seconds) " +
                    "ON CONFLICT (org_id, month) DO UPDATE SET seconds = runners_usage.seconds + EXCLUDED.seconds",
            ).bind("org_id", orgId).bind("month", month).bind("seconds", seconds),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.lockOrg(orgId: Uuid) {
        fetchAll(
            Statement.create("SELECT pg_advisory_xact_lock(hashtext(:key))").bind("key", "runners:$orgId"),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.countHolding(orgId: Uuid, except: Uuid?): Int = fetchAll(
        Statement.create(
            "SELECT COUNT(*) AS n FROM runners_sessions WHERE org_id = :org_id " +
                "AND state IN ('creating', 'running', 'pausing', 'resuming') AND id <> :except",
        ).bind("org_id", orgId).bind("except", except ?: Uuid.NIL),
    ).getOrThrow().first().get("n").asInt()

    /** Binds every column of an insert (sqlx4k refuses a name the statement does not use). */
    private fun Statement.bindInsert(session: CloudSession): Statement = bindUpdate(session)
        .bind("org_id", session.orgId)
        .bind("owner_id", session.ownerId)
        .bind("name", session.name)
        .bind("agent", session.agent.wire)
        .bind("model", session.model)
        .bind("prompt", session.prompt)
        .bind("repository_url", session.repository?.url)
        .bind("repository_ref", session.repository?.ref)
        .bind("idle_timeout_minutes", session.limits.idleTimeoutMinutes)
        .bind("max_run_minutes", session.limits.maxRunMinutes)
        .bind("restricted_egress", session.limits.restrictedEgress)
        .bind("created_at", session.createdAt)

    /** Binds the columns a reconciler or heartbeat write changes. */
    private fun Statement.bindUpdate(session: CloudSession): Statement = bind("id", session.id)
        .bind("state", session.state.wire)
        .bind("desired_state", session.desiredState.wire)
        .bind("phase", session.phase)
        .bind("epoch", session.epoch)
        .bind("failure_code", session.failure?.code)
        .bind("failure_message", session.failure?.message)
        .bind("updated_at", session.updatedAt)
        .bind("execution_started_at", session.executionStartedAt)
        .bind("ready_at", session.readyAt)
        .bind("last_heartbeat_at", session.lastHeartbeatAt)
        .bind("last_activity_at", session.lastActivityAt)
        .bind("paused_at", session.pausedAt)
        .bind("terminated_at", session.terminatedAt)
        .bind("pausing_since", session.pausingSince)
        .bind("runner_stopped_at", session.runnerStoppedAt)
        .bind("runner_token_hash", session.runnerTokenHash)

    private fun ResultSet.Row.toSession(): CloudSession {
        val url = get("repository_url").asStringOrNull()
        val failureCode = get("failure_code").asStringOrNull()
        return CloudSession(
            id = get("id").asUuid(),
            orgId = get("org_id").asUuid(),
            ownerId = get("owner_id").asString(),
            name = get("name").asString(),
            agent = AgentKind.fromWire(get("agent").asString()) ?: AgentKind.SHELL,
            repository = url?.let { RepositorySpec(it, get("repository_ref").asStringOrNull()) },
            state = SessionState.fromWire(get("state").asString()),
            desiredState = DesiredState.fromWire(get("desired_state").asString()),
            phase = get("phase").asStringOrNull(),
            epoch = get("epoch").asLong(),
            generation = get("generation").asLong(),
            failure = failureCode?.let { SessionFailure(it, get("failure_message").asStringOrNull().orEmpty()) },
            limits = SessionLimits(
                idleTimeoutMinutes = get("idle_timeout_minutes").asInt(),
                maxRunMinutes = get("max_run_minutes").asInt(),
                restrictedEgress = get("restricted_egress").asBoolean(),
            ),
            createdAt = get("created_at").asInstant(),
            updatedAt = get("updated_at").asInstant(),
            executionStartedAt = get("execution_started_at").asInstantOrNull(),
            readyAt = get("ready_at").asInstantOrNull(),
            lastHeartbeatAt = get("last_heartbeat_at").asInstantOrNull(),
            lastActivityAt = get("last_activity_at").asInstantOrNull(),
            pausedAt = get("paused_at").asInstantOrNull(),
            terminatedAt = get("terminated_at").asInstantOrNull(),
            pausingSince = get("pausing_since").asInstantOrNull(),
            runnerStoppedAt = get("runner_stopped_at").asInstantOrNull(),
            runnerTokenHash = get("runner_token_hash").asStringOrNull(),
            model = get("model").asStringOrNull(),
            prompt = get("prompt").asStringOrNull(),
        )
    }

    private companion object {
        /** A placeholder for an unused timestamp parameter (PostgreSQL cannot read Instant.DISTANT_PAST). */
        val EPOCH: Instant = Instant.fromEpochSeconds(0)

        const val COLUMNS = "id, org_id, owner_id, name, agent, model, prompt, repository_url, repository_ref, state, desired_state, " +
            "phase, epoch, generation, failure_code, failure_message, idle_timeout_minutes, max_run_minutes, " +
            "restricted_egress, created_at, updated_at, execution_started_at, ready_at, last_heartbeat_at, " +
            "last_activity_at, paused_at, terminated_at, pausing_since, runner_stopped_at, runner_token_hash"
        const val VALUES = ":id, :org_id, :owner_id, :name, :agent, :model, :prompt, :repository_url, :repository_ref, :state, " +
            ":desired_state, :phase, :epoch, 0, :failure_code, :failure_message, :idle_timeout_minutes, " +
            ":max_run_minutes, :restricted_egress, :created_at, :updated_at, :execution_started_at, :ready_at, " +
            ":last_heartbeat_at, :last_activity_at, :paused_at, :terminated_at, :pausing_since, :runner_stopped_at, " +
            ":runner_token_hash"
    }
}
