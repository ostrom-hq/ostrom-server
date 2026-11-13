// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionState
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
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudSessionsRepository] on PostgreSQL (tables of [RunnersMigrations]).
 *
 * Every read and write of a session or of the usage runs confined to the session's organization ([inTenant], S2-01).
 * Only `runners_session_routes` is used across organizations, through the `SECURITY DEFINER` functions of
 * [RunnersMigrations] (version 3): the reconciler claims and releases due sessions there, and the runner channel finds
 * a session by its runner token there, before loading the session in its organization.
 *
 * The running cap is serialized per organization with a transaction-scoped advisory lock, so the count and the
 * write of two concurrent creations or resumes never interleave.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the table.
class CloudSessionsRepositoryImpl(private val driver: Driver) : CloudSessionsRepository {
    override suspend fun createWithinCap(session: CloudSession, maxRunning: Int): Boolean =
        driver.inTenant(session.orgId) {
            lockOrg(session.orgId)
            if (countHolding(session.orgId, null) >= maxRunning) return@inTenant false
            execute(
                Statement.create("""INSERT INTO runners_sessions ($COLUMNS) VALUES ($VALUES)""").bindInsert(session),
            ).getOrThrow()
            execute(
                Statement.create(
                    """
                    INSERT INTO runners_session_routes (session_id, org_id, runner_token_hash, next_reconcile_at)
                    VALUES (:id, :org_id, :hash, :now)
                    """,
                ).bind("id", session.id).bind("org_id", session.orgId).bind("hash", session.runnerTokenHash)
                    .bind("now", session.createdAt),
            ).getOrThrow()
            true
        }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? = driver.inTenant(orgId) { select(id) }

    override suspend fun getById(id: Uuid): CloudSession? = orgOf(id)?.let { get(it, id) }

    override suspend fun list(orgId: Uuid, ownerId: String, before: Uuid?, limit: Int): List<CloudSession> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (created_at, id) <
                (SELECT created_at, id FROM runners_sessions WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND owner_id = :owner_id$cursor ORDER
            BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("owner_id", ownerId).bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toSession() } }
    }

    override suspend fun executions(orgId: Uuid, ownerId: String, limit: Int): List<CloudSession> {
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND owner_id = :owner_id AND state IN
            ('creating', 'running', 'pausing', 'resuming') ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("owner_id", ownerId).bind("limit", limit)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toSession() } }
    }

    override suspend fun setDesiredState(
        orgId: Uuid,
        id: Uuid,
        desiredState: DesiredState,
        now: Instant,
    ): CloudSession? = driver.inTenant(orgId) {
        val updated = fetchAll(
            Statement.create(
                """
                UPDATE runners_sessions SET desired_state = :desired, generation = generation + 1, updated_at =
                :now WHERE id = :id AND org_id = :org_id AND state NOT IN ('terminated', 'failed') RETURNING
                $COLUMNS
                """,
            ).bind("desired", desiredState.wire).bind("now", now).bind("id", id).bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toSession()
        if (updated != null) lookAt(id, now)
        updated ?: select(id)
    }

    override suspend fun setGitHubAccess(
        orgId: Uuid,
        id: Uuid,
        access: GitHubAccessMode,
        now: Instant,
    ): CloudSession? = driver.inTenant(orgId) {
        fetchAll(
            Statement.create(
                """
                UPDATE runners_sessions SET github_access = :access, generation = generation + 1, updated_at = :now
                WHERE id = :id AND org_id = :org_id AND state NOT IN ('terminated', 'failed')
                    AND (:access <> 'none' OR state = 'paused')
                RETURNING $COLUMNS
                """,
            ).bind("access", access.wire).bind("now", now).bind("id", id).bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toSession()
    }

    override suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: Instant): ResumeOutcome =
        driver.inTenant(orgId) {
            lockOrg(orgId)
            val current = select(id) ?: return@inTenant ResumeOutcome.NotFound
            if (!current.state.holdsExecution && countHolding(orgId, id) >= maxRunning) {
                return@inTenant ResumeOutcome.AtCap
            }
            val updated = fetchAll(
                Statement.create(
                    """
                    UPDATE runners_sessions SET desired_state = 'running', generation = generation + 1,
                    updated_at = :now WHERE id = :id AND org_id = :org_id RETURNING $COLUMNS
                    """,
                ).bind("now", now).bind("id", id).bind("org_id", orgId),
            ).getOrThrow().first().toSession()
            lookAt(id, now)
            ResumeOutcome.Resumed(updated)
        }

    override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? =
        driver.inTenant(session.orgId) {
            val updated = fetchAll(
                Statement.create(
                    """
                    UPDATE runners_sessions SET state = :state, desired_state = :desired_state, phase = :phase,
                    epoch = :epoch, generation = generation + 1, failure_code = :failure_code, failure_message =
                    :failure_message, updated_at = :updated_at, execution_started_at = :execution_started_at,
                    ready_at = :ready_at, last_heartbeat_at = :last_heartbeat_at, last_activity_at =
                    GREATEST(last_activity_at, :last_activity_at), paused_at = :paused_at, terminated_at =
                    :terminated_at, pausing_since = :pausing_since, runner_stopped_at = :runner_stopped_at,
                    runner_token_hash = :runner_token_hash WHERE id = :id AND generation = :generation RETURNING
                    $COLUMNS
                    """,
                ).bindUpdate(session).bind("generation", session.generation),
            ).getOrThrow().firstOrNull()?.toSession()
            if (updated != null) {
                execute(
                    Statement.create(
                        """
                        UPDATE runners_session_routes SET runner_token_hash = :hash, next_reconcile_at = CASE WHEN
                        CAST(:has_next AS BOOLEAN) THEN LEAST(COALESCE(next_reconcile_at, :next_at), :next_at) ELSE
                        next_reconcile_at END WHERE session_id = :id
                        """,
                    ).bind("hash", session.runnerTokenHash)
                        .bind("has_next", nextReconcileAt != null)
                        .bind("next_at", nextReconcileAt ?: session.updatedAt)
                        .bind("id", session.id),
                ).getOrThrow()
            }
            updated
        }

    override suspend fun touchActivity(id: Uuid, at: Instant) {
        val orgId = orgOf(id) ?: return
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    """
                    UPDATE runners_sessions SET last_activity_at = GREATEST(COALESCE(last_activity_at, :at), :at)
                    WHERE id = :id
                    """,
                ).bind("at", at).bind("id", id),
            ).getOrThrow()
        }
    }

    override suspend fun findByRunnerTokenHash(tokenHash: String): CloudSession? =
        route(tokenHash)

    override suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession> =
        driver.fetchAll(
            Statement.create(
                """
                SELECT session_id, org_id FROM runners_claim_due(CAST(:owner AS TEXT), CAST(:until AS TIMESTAMPTZ),
                CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER))
                """,
            ).bind("owner", owner).bind("until", leaseUntil).bind("now", now).bind("limit", limit),
        ).getOrThrow().mapNotNull { get(it.get("org_id").asUuid(), it.get("session_id").asUuid()) }

    override suspend fun release(id: Uuid, owner: String, nextReconcileAt: Instant?) {
        // An API write or a heartbeat during the pass moves next_reconcile_at after claimed_at: that request for a
        // new look is kept (the earliest of the two wins), never erased by the end of the pass.
        driver.fetchAll(
            Statement.create(
                """
                SELECT runners_release(CAST(:id AS UUID), CAST(:owner AS TEXT), CAST(:has_next AS BOOLEAN),
                CAST(:next_at AS TIMESTAMPTZ))
                """,
            ).bind("has_next", nextReconcileAt != null)
                .bind("next_at", nextReconcileAt ?: EPOCH)
                .bind("id", id).bind("owner", owner),
        ).getOrThrow()
    }

    override suspend fun usageSeconds(orgId: Uuid, month: String): Long = driver.inTenant(orgId) {
        fetchAll(
            Statement.create("""SELECT seconds FROM runners_usage WHERE org_id = :org_id AND month = :month""")
                .bind("org_id", orgId).bind("month", month),
        ).getOrThrow().firstOrNull()?.let { it.get("seconds").asLong() } ?: 0
    }

    override suspend fun addUsage(orgId: Uuid, month: String, seconds: Long) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    """
                    INSERT INTO runners_usage (org_id, month, seconds) VALUES (:org_id, :month, :seconds) ON
                    CONFLICT (org_id, month) DO UPDATE SET seconds = runners_usage.seconds + EXCLUDED.seconds
                    """,
                ).bind("org_id", orgId).bind("month", month).bind("seconds", seconds),
            ).getOrThrow()
        }
    }

    /** The organization of a session, from the one table used across organizations (`runners_session_org`). */
    private suspend fun orgOf(id: Uuid): Uuid? = driver.fetchAll(
        Statement.create("""SELECT runners_session_org(CAST(:id AS UUID)) AS org_id""").bind("id", id),
    ).getOrThrow().first().get("org_id").asUuidOrNull()

    /** The session whose runner token hashes to [tokenHash] (`runners_session_by_token`), in its organization. */
    private suspend fun route(tokenHash: String): CloudSession? {
        val row = driver.fetchAll(
            Statement.create("""SELECT session_id, org_id FROM runners_session_by_token(CAST(:key AS TEXT))""")
                .bind("key", tokenHash),
        ).getOrThrow().firstOrNull() ?: return null
        return get(row.get("org_id").asUuid(), row.get("session_id").asUuid())
    }

    /** Asks the reconciler for a look at [id] at [now] (an API write). */
    private suspend fun QueryExecutor.lookAt(id: Uuid, now: Instant) {
        execute(
            Statement.create("""UPDATE runners_session_routes SET next_reconcile_at = :now WHERE session_id = :id""")
                .bind("now", now).bind("id", id),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.select(id: Uuid): CloudSession? = fetchAll(
        Statement.create("""SELECT $COLUMNS FROM runners_sessions WHERE id = :id""").bind("id", id),
    ).getOrThrow().firstOrNull()?.toSession()

    private suspend fun QueryExecutor.lockOrg(orgId: Uuid) {
        fetchAll(
            Statement.create("""SELECT pg_advisory_xact_lock(hashtext(:key))""").bind("key", "runners:$orgId"),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.countHolding(orgId: Uuid, except: Uuid?): Int = fetchAll(
        Statement.create(
            """
            SELECT COUNT(*) AS n FROM runners_sessions WHERE org_id = :org_id AND state IN ('creating',
            'running', 'pausing', 'resuming') AND id <> :except
            """,
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
        .bind("github_access", session.githubAccess.wire)

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
            agent = get("agent").asString().let { wire -> AgentKind.entries.first { it.wire == wire } },
            repository = url?.let { RepositorySpec(it, get("repository_ref").asStringOrNull()) },
            state = SessionState.fromWire(get("state").asString()),
            desiredState = DesiredState.fromWire(get("desired_state").asString()),
            phase = get("phase").asStringOrNull(),
            epoch = get("epoch").asLong(),
            generation = get("generation").asLong(),
            failure = failureCode?.let { SessionFailure(it, get("failure_message").asString()) },
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
            // A name this version does not know grants nothing.
            githubAccess = GitHubAccessMode.fromWire(get("github_access").asString()) ?: GitHubAccessMode.NONE,
        )
    }

    private companion object {
        /** A placeholder for an unused timestamp parameter (PostgreSQL cannot read Instant.DISTANT_PAST). */
        private val EPOCH: Instant = Instant.fromEpochSeconds(0)

        const val COLUMNS = """
            id, org_id, owner_id, name, agent, model, prompt, repository_url, repository_ref, state, desired_state,
            phase, epoch, generation, failure_code, failure_message, idle_timeout_minutes, max_run_minutes,
            restricted_egress, created_at, updated_at, execution_started_at, ready_at, last_heartbeat_at,
            last_activity_at, paused_at, terminated_at, pausing_since, runner_stopped_at, runner_token_hash,
            github_access
        """
        const val VALUES = """
            :id, :org_id, :owner_id, :name, :agent, :model, :prompt, :repository_url, :repository_ref, :state,
            :desired_state, :phase, :epoch, 0, :failure_code, :failure_message, :idle_timeout_minutes,
            :max_run_minutes, :restricted_egress, :created_at, :updated_at, :execution_started_at, :ready_at,
            :last_heartbeat_at, :last_activity_at, :paused_at, :terminated_at, :pausing_since, :runner_stopped_at,
            :runner_token_hash, :github_access
        """
    }
}
