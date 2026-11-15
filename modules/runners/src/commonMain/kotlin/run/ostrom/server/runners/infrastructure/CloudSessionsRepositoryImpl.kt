// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudAttribution
import run.ostrom.server.runners.domain.CloudHumanWait
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DeletionState
import run.ostrom.server.runners.domain.WorkspaceDeletion
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CloudSessionWritten
import run.ostrom.server.runners.domain.NoCloudSessionWritten
import run.ostrom.server.runners.domain.CreateKey
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.KeyedCreateOutcome
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.SessionRef
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.kernel.audit.DiscardingTransactionalOrgAuditTrail
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.json.Json
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
 * Once a write of a session commits ([compareAndSet], [setDesiredState]), [written] is told, so that the inference
 * credentials of an execution that ended are revoked right after it (`runners.cloud.inference.v1`).
 *
 * An API write's audit entry is written in the transaction of the write it tells ([audit]): the write does not happen
 * when its entry cannot be written, and an entry is never kept for a write that did not happen.
 *
 * @param driver The database.
 * @param written Told of every session written by [compareAndSet] and [setDesiredState], once committed.
 * @param audit Writes an API write's audit entry in its transaction.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the table.
class CloudSessionsRepositoryImpl(
    private val driver: Driver,
    private val written: CloudSessionWritten = NoCloudSessionWritten,
    private val audit: TransactionalOrgAuditTrail = DiscardingTransactionalOrgAuditTrail(),
) : CloudSessionsRepository {
    override suspend fun createWithinCap(session: CloudSession, maxRunning: Int, journal: OrgAuditRecord?): Boolean =
        driver.inTenant(session.orgId) {
            lockOrg(session.orgId)
            if (countHolding(session.orgId, null) >= maxRunning) return@inTenant false
            insertSession(session)
            writeJournal(journal)
            true
        }

    override suspend fun createKeyed(
        session: CloudSession,
        maxRunning: Int,
        key: CreateKey,
        journal: OrgAuditRecord?,
    ): KeyedCreateOutcome =
        driver.inTenant(session.orgId) {
            // Serialized per organization like the cap: one of concurrent requests with a key creates, the others
            // then read its record.
            lockOrg(session.orgId)
            val scope = "org_id = :org_id AND member_id = :member_id AND idempotency_key = :key"
            val existing = fetchAll(
                Statement.create(
                    """
                    SELECT request_digest, session_id, expires_at > :now AS live FROM runners_create_keys
                    WHERE $scope
                    """,
                ).bindKey(session, key).bind("now", session.createdAt),
            ).getOrThrow().firstOrNull()
            if (existing != null && existing.get("live").asBoolean()) {
                if (existing.get("request_digest").asString() != key.requestDigest) {
                    return@inTenant KeyedCreateOutcome.Conflict
                }
                val replayed = select(existing.get("session_id").asUuid())
                return@inTenant replayed?.let { KeyedCreateOutcome.Replayed(it) } ?: KeyedCreateOutcome.Conflict
            }
            if (existing != null) {
                execute(Statement.create("DELETE FROM runners_create_keys WHERE $scope").bindKey(session, key))
                    .getOrThrow()
            }
            if (countHolding(session.orgId, null) >= maxRunning) return@inTenant KeyedCreateOutcome.AtCap
            insertSession(session)
            execute(
                Statement.create(
                    """
                    INSERT INTO runners_create_keys (org_id, member_id, idempotency_key, request_digest, session_id,
                        created_at, expires_at) VALUES (:org_id, :member_id, :key, :digest, :session_id, :now,
                        :expires_at)
                    """,
                ).bindKey(session, key).bind("digest", key.requestDigest).bind("session_id", session.id)
                    .bind("now", session.createdAt).bind("expires_at", key.expiresAt),
            ).getOrThrow()
            writeJournal(journal)
            KeyedCreateOutcome.Created
        }

    // The one deletion across organizations, through the function of RunnersMigrations version 19.
    override suspend fun purgeExpiredCreateKeys(): Long = driver.fetchAll(
        Statement.create("SELECT runners_purge_create_keys() AS n"),
    ).getOrThrow().first().get("n").asLong()

    override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? = driver.inTenant(orgId) { select(id) }

    override suspend fun getById(id: Uuid): CloudSession? = orgOf(id)?.let { get(it, id) }

    override suspend fun list(
        orgId: Uuid,
        ownerId: String,
        before: Uuid?,
        limit: Int,
        kinds: Set<SessionKind>,
    ): List<CloudSession> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (created_at, id) <
                (SELECT created_at, id FROM runners_sessions WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND owner_id = :owner_id
            AND kind = ANY(CAST(:kinds AS TEXT[]))$cursor ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("owner_id", ownerId).bind("limit", limit)
            .bind("kinds", kinds.map { it.wire }.sorted().joinToString(",", "{", "}"))
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
        journal: OrgAuditRecord?,
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
        if (updated != null) {
            lookAt(id, now)
            writeJournal(journal)
        }
        updated ?: select(id)
    }?.also { written.written(it) }

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

    override suspend fun resumeWithinCap(
        orgId: Uuid,
        id: Uuid,
        maxRunning: Int,
        now: Instant,
        journal: OrgAuditRecord?,
    ): ResumeOutcome =
        driver.inTenant(orgId) {
            lockOrg(orgId)
            val current = select(id) ?: return@inTenant ResumeOutcome.NotFound
            // A remote session runs on its owner's device: it holds no place under the cap.
            val capped = current.kind == SessionKind.CLOUD && !current.state.holdsExecution
            if (capped && countHolding(orgId, id) >= maxRunning) {
                return@inTenant ResumeOutcome.AtCap
            }
            // A session deleted or ended since it was read is not brought back: it is gone, as the owner sees it.
            val updated = fetchAll(
                Statement.create(
                    """
                    UPDATE runners_sessions SET desired_state = 'running', generation = generation + 1,
                    updated_at = :now WHERE id = :id AND org_id = :org_id AND desired_state <> 'terminated' AND
                    state NOT IN ('terminated', 'failed', 'transferring') AND transfer_id IS NULL RETURNING $COLUMNS
                    """,
                ).bind("now", now).bind("id", id).bind("org_id", orgId),
            ).getOrThrow().firstOrNull() ?: return@inTenant ResumeOutcome.NotFound
            lookAt(id, now)
            writeJournal(journal)
            ResumeOutcome.Resumed(updated.toSession())
        }

    override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? =
        driver.inTenant(session.orgId) { compareAndSetIn(this, session, nextReconcileAt) }
            ?.also { written.written(it) }

    /**
     * Writes a session if nobody changed it since it was read, and asks the reconciler for its next look
     * ([compareAndSet]), inside a transaction the caller runs: a transfer writes it with its own rows.
     *
     * @param executor The tenant transaction.
     * @param session The session as it must be stored.
     * @param nextReconcileAt When the reconciler must look at it again.
     *
     * @return The stored session, or null when another writer changed it first.
     */
    internal suspend fun compareAndSetIn(
        executor: QueryExecutor,
        session: CloudSession,
        nextReconcileAt: Instant?,
    ): CloudSession? = with(executor) {
        val updated = fetchAll(
            Statement.create(
                """
                UPDATE runners_sessions SET state = :state, desired_state = :desired_state, phase = :phase,
                epoch = :epoch, generation = generation + 1, failure_code = :failure_code, failure_message =
                :failure_message, failure_retryable = :failure_retryable, updated_at = :updated_at,
                execution_started_at = :execution_started_at, ready_at = :ready_at, last_heartbeat_at =
                :last_heartbeat_at, last_activity_at = GREATEST(last_activity_at, :last_activity_at),
                paused_at = :paused_at, terminated_at = :terminated_at, pausing_since = :pausing_since,
                runner_stopped_at = :runner_stopped_at, runner_token_hash = :runner_token_hash,
                background_since = :background_since, background_seen_at = :background_seen_at, profile =
                :profile, cluster_unreachable_since = :cluster_unreachable_since, owner_id = :owner_id,
                transfer_id = :transfer_id, untrusted_workspace = :untrusted_workspace,
                github_access = :github_access, expires_at = :expires_at, terminated_reason = :terminated_reason,
                expiry_backfill = :expiry_backfill,
                workspace_deletion_state = :workspace_deletion_state,
                workspace_claim_deleted_at = :workspace_claim_deleted_at, workspace_volume_deleted_at =
                :workspace_volume_deleted_at, workspace_volume_name = :workspace_volume_name,
                workspace_volume_handle = :workspace_volume_handle, workspace_problem_code =
                :workspace_problem_code, workspace_problem_message = :workspace_problem_message,
                workspace_problem_retryable = :workspace_problem_retryable
                WHERE id = :id AND generation = :generation RETURNING
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

    // The one read across organizations by device, through the function of RunnersMigrations version 22.
    override suspend fun hostedOn(deviceId: Uuid, live: Boolean): List<SessionRef> = driver.fetchAll(
        Statement.create(
            "SELECT session_id, org_id FROM runners_sessions_on_device(CAST(:device AS UUID), CAST(:live AS BOOLEAN))",
        ).bind("device", deviceId).bind("live", live),
    ).getOrThrow().map { SessionRef(it.get("org_id").asUuid(), it.get("session_id").asUuid()) }

    // On the unique index of version 27, inside the organization.
    override suspend fun findLocal(orgId: Uuid, launchId: String): CloudSession? = driver.inTenant(orgId) {
        fetchAll(
            Statement.create(
                "SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND launch_id = :launch_id",
            ).bind("org_id", orgId).bind("launch_id", launchId),
        ).getOrThrow().firstOrNull()?.toSession()
    }

    // Read every second by each waiting daemon: the partial index of version 23 holds only the waiting sessions.
    override suspend fun daemonOrders(orgId: Uuid, deviceId: Uuid, limit: Int): List<CloudSession> {
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM runners_sessions WHERE org_id = :org_id AND host_device_id = :device AND
            phase = :phase AND desired_state = 'running' AND host_folder_id IS NOT NULL
            ORDER BY updated_at, id LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("device", deviceId).bind("phase", RemoteSessionRules.WAITING_HOST)
            .bind("limit", limit)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toSession() } }
    }

    override suspend fun endOnRemovedDevice(ref: SessionRef, deviceId: Uuid, now: Instant): Boolean =
        driver.inTenant(ref.orgId) {
            // Its remote sessions end with the device: they could only ever run there. The reconciler finishes the
            // termination; every one of them loses its host, so that it can never run again.
            val ended = execute(
                Statement.create(
                    """
                    UPDATE runners_sessions SET desired_state = 'terminated', terminated_reason = :reason,
                    generation = generation + 1, updated_at = :now WHERE id = :id AND host_device_id = :device AND
                    state NOT IN ('terminated', 'failed') AND desired_state <> 'terminated'
                    """,
                ).bind("reason", RemoteSessionRules.DEVICE_REMOVED).bind("now", now).bind("id", ref.id)
                    .bind("device", deviceId),
            ).getOrThrow() == 1L
            execute(
                Statement.create(
                    """
                    UPDATE runners_sessions SET host_device_id = NULL, generation = generation + 1
                    WHERE id = :id AND host_device_id = :device
                    """,
                ).bind("id", ref.id).bind("device", deviceId),
            ).getOrThrow()
            if (ended) lookAt(ref.id, now)
            ended
        }

    override suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession> =
        driver.fetchAll(
            Statement.create(
                """
                SELECT session_id, org_id FROM runners_claim_due(CAST(:owner AS TEXT), CAST(:until AS TIMESTAMPTZ),
                CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER))
                """,
            ).bind("owner", owner).bind("until", leaseUntil).bind("now", now).bind("limit", limit),
        ).getOrThrow().mapNotNull { get(it.get("org_id").asUuid(), it.get("session_id").asUuid()) }

    // The function returns ids and organizations only; each session is then read in its own organization.
    override suspend fun withoutProfile(after: Uuid?, limit: Int): List<SessionRef> = driver.fetchAll(
        Statement.create(
            """
            SELECT session_id, org_id FROM runners_sessions_without_profile(CAST(:after AS UUID),
            CAST(:limit AS INTEGER))
            """,
        ).bind("after", after).bind("limit", limit),
    ).getOrThrow().map { SessionRef(it.get("org_id").asUuid(), it.get("session_id").asUuid()) }

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

    /** Writes an API write's audit entry in this transaction; a write of the server itself has none. */
    private suspend fun Transaction.writeJournal(record: OrgAuditRecord?) {
        if (record != null) audit.record(this, record)
    }

    private suspend fun QueryExecutor.select(id: Uuid): CloudSession? = fetchAll(
        Statement.create("""SELECT $COLUMNS FROM runners_sessions WHERE id = :id""").bind("id", id),
    ).getOrThrow().firstOrNull()?.toSession()

    private suspend fun QueryExecutor.insertSession(session: CloudSession) {
        execute(
            Statement.create("""INSERT INTO runners_sessions ($INSERTED) VALUES ($VALUES)""").bindInsert(session),
        ).getOrThrow()
        // A remote session in `bypass` mode starts with its owner's standing approval (TeamCode's permissionMode).
        session.permissionBypass?.let { bypass ->
            execute(
                Statement.create(
                    """
                    UPDATE runners_sessions SET bypass_by = :by, bypass_credential_id = :credential, bypass_at = :at
                    WHERE id = :id
                    """,
                ).bind("by", bypass.by).bind("credential", bypass.credentialId).bind("at", bypass.at)
                    .bind("id", session.id),
            ).getOrThrow()
        }
        execute(
            Statement.create(
                """
                INSERT INTO runners_session_routes (session_id, org_id, runner_token_hash, next_reconcile_at)
                VALUES (:id, :org_id, :hash, :now)
                """,
            ).bind("id", session.id).bind("org_id", session.orgId).bind("hash", session.runnerTokenHash)
                .bind("now", session.createdAt),
        ).getOrThrow()
    }

    private fun Statement.bindKey(session: CloudSession, key: CreateKey): Statement =
        bind("org_id", session.orgId).bind("member_id", key.memberId ?: session.ownerId).bind("key", key.key)

    private suspend fun QueryExecutor.lockOrg(orgId: Uuid) {
        fetchAll(
            Statement.create("""SELECT pg_advisory_xact_lock(hashtext(:key))""").bind("key", "runners:$orgId"),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.countHolding(orgId: Uuid, except: Uuid?): Int = fetchAll(
        Statement.create(
            """
            SELECT COUNT(*) AS n FROM runners_sessions WHERE org_id = :org_id AND state IN ('creating',
            'running', 'pausing', 'resuming') AND id <> :except AND kind = 'cloud'
            """,
        ).bind("org_id", orgId).bind("except", except ?: Uuid.NIL),
    ).getOrThrow().first().get("n").asInt()

    /** Binds every column of an insert (sqlx4k refuses a name the statement does not use). */
    private fun Statement.bindInsert(session: CloudSession): Statement = bindUpdate(session)
        .bind("org_id", session.orgId)
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
        .bind("mode", session.mode.wire)
        .bind("effort", session.effort)
        .bind("kind", session.kind.wire)
        .bind("host_device_id", session.host?.deviceId)
        .bind("host_directory_name", session.host?.directoryName)
        .bind("host_permission_mode", session.host?.permissionMode)
        .bind("host_folder_id", session.host?.folderId)
        .bind("host_resume_session_id", session.host?.resumeSessionId)
        .bind("launch_id", session.launchId)
        .bind("created_by_initiator_id", session.createdBy?.initiatorId)
        .bind("created_by_credential_id", session.createdBy?.credentialId)
        .bind("created_by_effective_member_id", session.createdBy?.effectiveMemberId)
        .bind(
            "environment",
            session.environment?.let { RECIPE_JSON.encodeToString(CloudEnvironmentSpec.serializer(), it) },
        )

    /** Binds the columns a reconciler or heartbeat write changes. */
    private fun Statement.bindUpdate(session: CloudSession): Statement = bind("id", session.id)
        .bind("state", session.state.wire)
        .bind("desired_state", session.desiredState.wire)
        .bind("phase", session.phase)
        .bind("epoch", session.epoch)
        .bind("failure_code", session.failure?.code)
        .bind("failure_message", session.failure?.message)
        .bind("failure_retryable", session.failure?.retryable ?: false)
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
        .bind("background_since", session.backgroundSince)
        .bind("background_seen_at", session.backgroundSeenAt)
        .bind("profile", session.profile?.let { RECIPE_JSON.encodeToString(SessionProfile.serializer(), it) })
        .bind("cluster_unreachable_since", session.clusterUnreachableSince)
        .bind("owner_id", session.ownerId)
        .bind("transfer_id", session.transferId)
        .bind("untrusted_workspace", session.untrustedWorkspace)
        .bind("github_access", session.githubAccess.wire)
        .bind("expires_at", session.expiresAt)
        .bind("expiry_backfill", session.expiryBackfill)
        .bind("terminated_reason", session.terminatedReason)
        .bindDeletion(session.workspaceDeletion)

    private fun Statement.bindDeletion(deletion: WorkspaceDeletion?): Statement =
        bind("workspace_deletion_state", deletion?.let { it.state.wire })
            .bind("workspace_claim_deleted_at", deletion?.claimDeletedAt)
            .bind("workspace_volume_deleted_at", deletion?.volumeDeletedAt)
            .bind("workspace_volume_name", deletion?.volumeName)
            .bind("workspace_volume_handle", deletion?.volumeHandle)
            .bind("workspace_problem_code", deletion?.problem?.code)
            .bind("workspace_problem_message", deletion?.problem?.message)
            .bind("workspace_problem_retryable", deletion?.problem?.retryable ?: false)

    private fun ResultSet.Row.toSession(): CloudSession {
        val url = get("repository_url").asStringOrNull()
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
            failure = get("failure_code").asStringOrNull()?.let {
                SessionFailure(it, get("failure_message").asString(), get("failure_retryable").asBoolean())
            },
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
            backgroundSince = get("background_since").asInstantOrNull(),
            backgroundSeenAt = get("background_seen_at").asInstantOrNull(),
            model = get("model").asStringOrNull(),
            prompt = get("prompt").asStringOrNull(),
            // A name this version does not know grants nothing.
            githubAccess = GitHubAccessMode.fromWire(get("github_access").asString()) ?: GitHubAccessMode.NONE,
            environment = get("environment").asStringOrNull()
                ?.let { RECIPE_JSON.decodeFromString(CloudEnvironmentSpec.serializer(), it) },
            profile = get("profile").asStringOrNull()
                ?.let { RECIPE_JSON.decodeFromString(SessionProfile.serializer(), it) },
            clusterUnreachableSince = get("cluster_unreachable_since").asInstantOrNull(),
            transferId = get("transfer_id").asUuidOrNull(),
            untrustedWorkspace = get("untrusted_workspace").asBoolean(),
            expiresAt = get("expires_at").asInstantOrNull(),
            expiryBackfill = get("expiry_backfill").asBoolean(),
            terminatedReason = get("terminated_reason").asStringOrNull(),
            // A mode this version does not know is a terminal: it never takes a message it cannot deliver.
            mode = SessionMode.fromWire(get("mode").asString()) ?: SessionMode.TERMINAL,
            effort = get("effort").asStringOrNull(),
            permissionBypass = bypass(),
            waiting = waiting(),
            workspaceDeletion = toDeletion(),
            // A kind this version does not know is a cloud session's: it never hosts it on a device.
            kind = SessionKind.fromWire(get("kind").asString()) ?: SessionKind.CLOUD,
            host = host(),
            createdBy = createdBy(),
            launchId = get("launch_id").asStringOrNull(),
        )
    }

    // Recorded since runners.cloud.onbehalf.v1: an older session has none, nor a row missing either member.
    private fun ResultSet.Row.createdBy(): CloudAttribution? {
        val initiator = get("created_by_initiator_id").asStringOrNull()
        val effective = get("created_by_effective_member_id").asStringOrNull()
        if (initiator == null || effective == null) return null
        return CloudAttribution(initiator, get("created_by_credential_id").asStringOrNull(), effective)
    }

    private fun ResultSet.Row.host(): RemoteHost? = get("host_permission_mode").asStringOrNull()?.let {
        RemoteHost(
            deviceId = get("host_device_id").asUuidOrNull(),
            directoryName = get("host_directory_name").asStringOrNull(),
            permissionMode = it,
            folderId = get("host_folder_id").asStringOrNull(),
            resumeSessionId = get("host_resume_session_id").asStringOrNull(),
        )
    }

    private fun ResultSet.Row.bypass(): PermissionBypass? = get("bypass_by").asStringOrNull()?.let {
        PermissionBypass(it, get("bypass_credential_id").asStringOrNull(), get("bypass_at").asInstant())
    }

    private fun ResultSet.Row.waiting(): CloudHumanWait? = get("waiting_reason").asStringOrNull()?.let {
        CloudHumanWait(
            reason = it,
            requestId = get("waiting_request_id").asString(),
            since = get("waiting_since").asInstant(),
            eventSeq = get("waiting_event_seq").asLong(),
            epoch = get("waiting_epoch").asLong(),
        )
    }

    private fun ResultSet.Row.toDeletion(): WorkspaceDeletion? {
        val state = get("workspace_deletion_state").asStringOrNull() ?: return null
        val problem = get("workspace_problem_code").asStringOrNull()?.let {
            SessionFailure(
                it,
                get("workspace_problem_message").asString(),
                get("workspace_problem_retryable").asBoolean(),
            )
        }
        return WorkspaceDeletion(
            state = DeletionState.fromWire(state),
            claimDeletedAt = get("workspace_claim_deleted_at").asInstantOrNull(),
            volumeDeletedAt = get("workspace_volume_deleted_at").asInstantOrNull(),
            volumeName = get("workspace_volume_name").asStringOrNull(),
            volumeHandle = get("workspace_volume_handle").asStringOrNull(),
            problem = problem,
        )
    }

    private companion object {
        /** A placeholder for an unused timestamp parameter (PostgreSQL cannot read Instant.DISTANT_PAST). */
        private val EPOCH: Instant = Instant.fromEpochSeconds(0)

        /** The recipe's JSON on the row: a field a later version adds is ignored by this one. */
        private val RECIPE_JSON = Json { ignoreUnknownKeys = true }

        /** What a creation writes. */
        const val INSERTED = """
            id, org_id, owner_id, name, agent, model, prompt, repository_url, repository_ref, state, desired_state,
            phase, epoch, generation, failure_code, failure_message, idle_timeout_minutes, max_run_minutes,
            restricted_egress, created_at, updated_at, execution_started_at, ready_at, last_heartbeat_at,
            last_activity_at, paused_at, terminated_at, pausing_since, runner_stopped_at, runner_token_hash,
            github_access, background_since, background_seen_at, environment, failure_retryable, profile,
            cluster_unreachable_since, transfer_id, untrusted_workspace, expires_at, terminated_reason,
            expiry_backfill, workspace_deletion_state, workspace_claim_deleted_at, workspace_volume_deleted_at,
            workspace_volume_name, workspace_volume_handle, workspace_problem_code, workspace_problem_message,
            workspace_problem_retryable, mode, effort, kind, host_device_id, host_directory_name,
            host_permission_mode, host_folder_id, created_by_initiator_id, created_by_credential_id,
            created_by_effective_member_id, host_resume_session_id, launch_id
        """

        /** What a read returns: also the Bypass and the wait, which only the conversation's store writes. */
        const val COLUMNS = """$INSERTED, bypass_by, bypass_credential_id, bypass_at, waiting_reason,
            waiting_request_id, waiting_since, waiting_event_seq, waiting_epoch"""
        const val VALUES = """
            :id, :org_id, :owner_id, :name, :agent, :model, :prompt, :repository_url, :repository_ref, :state,
            :desired_state, :phase, :epoch, 0, :failure_code, :failure_message, :idle_timeout_minutes,
            :max_run_minutes, :restricted_egress, :created_at, :updated_at, :execution_started_at, :ready_at,
            :last_heartbeat_at, :last_activity_at, :paused_at, :terminated_at, :pausing_since, :runner_stopped_at,
            :runner_token_hash, :github_access, :background_since, :background_seen_at, :environment,
            :failure_retryable, :profile, :cluster_unreachable_since, :transfer_id, :untrusted_workspace,
            :expires_at, :terminated_reason, :expiry_backfill, :workspace_deletion_state,
            :workspace_claim_deleted_at, :workspace_volume_deleted_at, :workspace_volume_name,
            :workspace_volume_handle, :workspace_problem_code, :workspace_problem_message,
            :workspace_problem_retryable, :mode, :effort, :kind, :host_device_id, :host_directory_name,
            :host_permission_mode, :host_folder_id, :created_by_initiator_id, :created_by_credential_id,
            :created_by_effective_member_id, :host_resume_session_id, :launch_id
        """
    }
}
