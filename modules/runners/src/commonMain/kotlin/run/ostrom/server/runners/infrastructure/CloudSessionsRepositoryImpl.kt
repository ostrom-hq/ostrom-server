// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // The port's statements, one function each, so each has its plan.

package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.audit.DiscardingTransactionalOrgAuditTrail
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.database.written
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionWritten
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateKey
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.KeyedCreateOutcome
import run.ostrom.server.runners.domain.NoCloudSessionWritten
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionRef
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudSessionsRepository] on PostgreSQL through Exposed (tables of [RunnersMigrations]).
 *
 * Every read and write of a session or of the usage runs confined to the session's organization
 * ([JdbcDatabase.inOrg], S2-01). Only `runners_session_routes` is used across organizations, through the `SECURITY
 * DEFINER` functions of [RunnersMigrations] (version 3): the reconciler claims and releases due sessions there, and the
 * runner channel finds a session by its runner token there, before loading the session in its organization.
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
 * @param database The JDBC pool.
 * @param written Told of every session written by [compareAndSet] and [setDesiredState], once committed.
 * @param audit Writes an API write's audit entry in its transaction.
 */
class CloudSessionsRepositoryImpl(
    private val database: JdbcDatabase,
    private val written: CloudSessionWritten = NoCloudSessionWritten,
    private val audit: TransactionalOrgAuditTrail = DiscardingTransactionalOrgAuditTrail(),
) : CloudSessionsRepository {
    override suspend fun createWithinCap(session: CloudSession, maxRunning: Int, journal: OrgAuditRecord?): Boolean =
        recording(session.orgId, journal) { record ->
            lockOrg(session.orgId)
            if (countHolding(session.orgId, null) >= maxRunning) return@recording false
            insertSession(session)
            record()
            true
        }

    override suspend fun createKeyed(
        session: CloudSession,
        maxRunning: Int,
        key: CreateKey,
        journal: OrgAuditRecord?,
    ): KeyedCreateOutcome = recording(session.orgId, journal) { record ->
        // Serialized per organization like the cap: one of concurrent requests with a key creates, the others
        // then read its record.
        lockOrg(session.orgId)
        val k = RunnersCreateKeys
        val member = key.memberId ?: session.ownerId
        val existing = k.select(k.requestDigest, k.sessionId, k.expiresAt).where { createKeyOf(session, member, key) }
            .firstOrNull()
        if (existing != null && existing[k.expiresAt] > session.createdAt) {
            if (existing[k.requestDigest] != key.requestDigest) return@recording KeyedCreateOutcome.Conflict
            val replayed = select(existing[k.sessionId])
            return@recording replayed?.let { KeyedCreateOutcome.Replayed(it) } ?: KeyedCreateOutcome.Conflict
        }
        if (existing != null) write { createKeyForgotten(session, member, key) }
        if (countHolding(session.orgId, null) >= maxRunning) return@recording KeyedCreateOutcome.AtCap
        insertSession(session)
        write { createKeyInsert(session, member, key) }
        record()
        KeyedCreateOutcome.Created
    }

    // The one deletion across organizations, through the function of RunnersMigrations version 19.
    override suspend fun purgeExpiredCreateKeys(): Long = database.query { scalar(purgeCreateKeys()) }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? = database.inOrg(orgId) { select(id) }

    override suspend fun getById(id: Uuid): CloudSession? = orgOf(id)?.let { get(it, id) }

    override suspend fun list(
        orgId: Uuid,
        ownerId: String,
        before: Uuid?,
        limit: Int,
        kinds: Set<SessionKind>,
    ): List<CloudSession> = database.inOrg(orgId) { sessionList(orgId, ownerId, before, limit, kinds).map(::toSession) }

    override suspend fun executions(orgId: Uuid, ownerId: String, limit: Int): List<CloudSession> =
        database.inOrg(orgId) { sessionExecutions(orgId, ownerId, limit).map(::toSession) }

    override suspend fun setDesiredState(
        orgId: Uuid,
        id: Uuid,
        desiredState: DesiredState,
        now: Instant,
        journal: OrgAuditRecord?,
    ): CloudSession? = recording(orgId, journal) { record ->
        val updated = returned { sessionDesired(orgId, id, desiredState, now) }.firstOrNull()?.let(::toSession)
        if (updated != null) {
            write { sessionLookAt(id, now) }
            record()
        }
        updated ?: select(id)
    }?.also { written.written(it) }

    override suspend fun setGitHubAccess(
        orgId: Uuid,
        id: Uuid,
        access: GitHubAccessMode,
        now: Instant,
    ): CloudSession? = database.inOrg(orgId) {
        returned { sessionGitHubAccess(orgId, id, access, now) }.firstOrNull()?.let(::toSession)
    }

    override suspend fun resumeWithinCap(
        orgId: Uuid,
        id: Uuid,
        maxRunning: Int,
        now: Instant,
        journal: OrgAuditRecord?,
    ): ResumeOutcome = recording(orgId, journal) { record ->
        lockOrg(orgId)
        val current = select(id) ?: return@recording ResumeOutcome.NotFound
        // A remote session runs on its owner's device: it holds no place under the cap.
        val capped = current.kind == SessionKind.CLOUD && !current.state.holdsExecution
        if (capped && countHolding(orgId, id) >= maxRunning) {
            return@recording ResumeOutcome.AtCap
        }
        // A session deleted or ended since it was read is not brought back: it is gone, as the owner sees it.
        val updated = returned { sessionResumed(orgId, id, now) }.firstOrNull()
            ?: return@recording ResumeOutcome.NotFound
        write { sessionLookAt(id, now) }
        record()
        ResumeOutcome.Resumed(toSession(updated))
    }

    override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? =
        database.inOrg(session.orgId) { compareAndSetIn(session, nextReconcileAt) }?.also { written.written(it) }

    override suspend fun touchActivity(id: Uuid, at: Instant) {
        val orgId = orgOf(id) ?: return
        database.inOrg(orgId) { write { sessionTouched(id, at) } }
    }

    override suspend fun findByRunnerTokenHash(tokenHash: String): CloudSession? = route(tokenHash)

    // The one read across organizations by device, through the function of RunnersMigrations version 22.
    override suspend fun hostedOn(deviceId: Uuid, live: Boolean): List<SessionRef> = database.query {
        val d = RunnersSessionsOnDevice
        d.of(deviceId, live).select(d.orgId, d.sessionId).map { SessionRef(it[d.orgId], it[d.sessionId]) }
    }

    // On the unique index of version 27, inside the organization.
    override suspend fun findLocal(orgId: Uuid, launchId: String): CloudSession? = database.inOrg(orgId) {
        sessionLocal(orgId, launchId).firstOrNull()?.let(::toSession)
    }

    // Read every second by each waiting daemon: the partial index of version 25 holds only the waiting sessions.
    override suspend fun daemonOrders(orgId: Uuid, deviceId: Uuid, limit: Int): List<CloudSession> =
        database.inOrg(orgId) { sessionDaemonOrders(orgId, deviceId, limit).map(::toSession) }

    override suspend fun endOnRemovedDevice(ref: SessionRef, deviceId: Uuid, now: Instant): Boolean =
        database.inOrg(ref.orgId) {
            // Its remote sessions end with the device: they could only ever run there. The reconciler finishes the
            // termination; every one of them loses its host, so that it can never run again.
            val ended = written { sessionDeviceEnded(ref.id, deviceId, now) } == 1
            write { sessionDeviceForgotten(ref.id, deviceId) }
            if (ended) write { sessionLookAt(ref.id, now) }
            ended
        }

    override suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession> {
        val claimed = database.query {
            val c = RunnersClaimDue
            c.of(owner, leaseUntil, now, limit).select(c.orgId, c.sessionId).map { it[c.orgId] to it[c.sessionId] }
        }
        return claimed.mapNotNull { (orgId, id) -> get(orgId, id) }
    }

    // The function returns ids and organizations only; each session is then read in its own organization.
    override suspend fun withoutProfile(after: Uuid?, limit: Int): List<SessionRef> = database.query {
        val p = RunnersSessionsWithoutProfile
        p.of(after, limit).select(p.orgId, p.sessionId).map { SessionRef(it[p.orgId], it[p.sessionId]) }
    }

    override suspend fun release(id: Uuid, owner: String, nextReconcileAt: Instant?) {
        // An API write or a heartbeat during the pass moves next_reconcile_at after claimed_at: that request for a
        // new look is kept (the earliest of the two wins), never erased by the end of the pass.
        database.query { Table.Dual.select(releaseClaim(id, owner, nextReconcileAt)).toList() }
    }

    override suspend fun usageSeconds(orgId: Uuid, month: String): Long = database.inOrg(orgId) {
        val u = RunnersUsage
        u.select(u.seconds).where { (u.orgId eq orgId) and (u.month eq month) }.firstOrNull()?.get(u.seconds) ?: 0L
    }

    override suspend fun addUsage(orgId: Uuid, month: String, seconds: Long) {
        database.inOrg(orgId) { write { usageAdded(orgId, month, seconds) } }
    }

    /**
     * Runs [change] in the organization's transaction. With a [journal], the change calls `record` once, as its last
     * statement, when it wrote: the entry is then appended to the organization's chain in the same transaction, and
     * kept if and only if it commits ([TransactionalOrgAuditTrail.recording]). Without one, `record` does nothing.
     */
    private suspend fun <T> recording(
        orgId: Uuid,
        journal: OrgAuditRecord?,
        change: JdbcTransaction.(record: () -> Boolean) -> T,
    ): T =
        if (journal == null) database.inOrg(orgId) { change { true } } else audit.recording(database, journal, change)

    /** The organization of a session, from the one table used across organizations (`runners_session_org`). */
    private suspend fun orgOf(id: Uuid): Uuid? = database.query { scalar(sessionOrg(id)) }

    /** The session whose runner token hashes to [tokenHash] (`runners_session_by_token`), in its organization. */
    private suspend fun route(tokenHash: String): CloudSession? {
        val b = RunnersSessionByToken
        val route = database.query {
            b.of(tokenHash).select(b.orgId, b.sessionId).firstOrNull()?.let { it[b.orgId] to it[b.sessionId] }
        } ?: return null
        return get(route.first, route.second)
    }
}

/**
 * Writes a session if nobody changed it since it was read, and asks the reconciler for its next look
 * ([CloudSessionsRepositoryImpl.compareAndSet]), inside a transaction the caller runs: a transfer writes it with its
 * own rows.
 *
 * @param session The session as it must be stored.
 * @param nextReconcileAt When the reconciler must look at it again.
 *
 * @return The stored session, or null when another writer changed it first.
 */
internal fun JdbcTransaction.compareAndSetIn(session: CloudSession, nextReconcileAt: Instant?): CloudSession? {
    val updated = returned { sessionStored(session) }.firstOrNull()?.let(::toSession)
    if (updated != null) write { sessionRouted(session, nextReconcileAt) }
    return updated
}

/** The session by id in the organization the transaction is confined to. */
private fun JdbcTransaction.select(id: Uuid): CloudSession? {
    val s = RunnersSessionsTable
    return s.select(s.columns).where { s.id eq id }.firstOrNull()?.let(::toSession)
}

/** The per-organization lock of the running cap, taken until the transaction ends. */
private fun JdbcTransaction.lockOrg(orgId: Uuid) = lockUntilCommit("runners:$orgId")

private fun JdbcTransaction.countHolding(orgId: Uuid, except: Uuid?): Long {
    val s = RunnersSessionsTable
    val held = s.id.count()
    return s.select(held)
        .where {
            (s.orgId eq orgId) and (s.state inList HOLDING) and (s.id neq (except ?: Uuid.NIL)) and
                (s.kind eq SessionKind.CLOUD.wire)
        }
        .single()[held]
}

private fun JdbcTransaction.insertSession(session: CloudSession) {
    write { sessionInsert(session) }
    write { sessionRouteInsert(session) }
}

/** The states in which a session holds an execution. */
private val HOLDING = listOf("creating", "running", "pausing", "resuming")

/** The states a session is over in. */
private val OVER = listOf("terminated", "failed")

/** The record of a creation key. */
private fun createKeyOf(session: CloudSession, member: String, key: CreateKey): Op<Boolean> {
    val k = RunnersCreateKeys
    return (k.orgId eq session.orgId) and (k.memberId eq member) and (k.idempotencyKey eq key.key)
}

/** A key record past its end, forgotten before the key is used again. */
internal fun StatementBuilder.createKeyForgotten(session: CloudSession, member: String, key: CreateKey) =
    RunnersCreateKeys.deleteWhere { createKeyOf(session, member, key) }

/** The record of a creation key, written with its session. */
internal fun StatementBuilder.createKeyInsert(
    session: CloudSession,
    member: String,
    key: CreateKey,
): InsertStatement<Number> = RunnersCreateKeys.insert {
    it[orgId] = session.orgId
    it[memberId] = member
    it[idempotencyKey] = key.key
    it[requestDigest] = key.requestDigest
    it[sessionId] = session.id
    it[createdAt] = session.createdAt
    it[expiresAt] = key.expiresAt
}

/** The member's sessions of these kinds, newest first, before the cursor's session when there is one. */
internal fun sessionList(orgId: Uuid, ownerId: String, before: Uuid?, limit: Int, kinds: Set<SessionKind>): Query {
    val s = RunnersSessionsTable
    val kind = s.kind eq anyFrom(kinds.map { it.wire }.sorted(), TextColumnType())
    val cursor: Op<Boolean> = if (before == null) {
        Op.TRUE
    } else {
        rowBefore(
            listOf(s.createdAt, s.id),
            s.select(s.createdAt, s.id).where { (s.id eq before) and (s.orgId eq orgId) },
        )
    }
    return s.select(s.columns).where { (s.orgId eq orgId) and (s.ownerId eq ownerId) and kind and cursor }
        .orderBy(s.createdAt to SortOrder.DESC, s.id to SortOrder.DESC)
        .limit(limit)
}

/** The member's sessions that hold an execution, newest first. */
internal fun sessionExecutions(orgId: Uuid, ownerId: String, limit: Int): Query {
    val s = RunnersSessionsTable
    return s.select(s.columns).where { (s.orgId eq orgId) and (s.ownerId eq ownerId) and (s.state inList HOLDING) }
        .orderBy(s.createdAt to SortOrder.DESC, s.id to SortOrder.DESC)
        .limit(limit)
}

/** The session of a launch: one at most per organization (the unique index of version 27). */
internal fun sessionLocal(orgId: Uuid, launchId: String): Query {
    val s = RunnersSessionsTable
    return s.select(s.columns).where { (s.orgId eq orgId) and (s.launchId eq launchId) }
}

/** A device daemon's queue: the sessions started through it that wait for their host, oldest request first. */
internal fun sessionDaemonOrders(orgId: Uuid, deviceId: Uuid, limit: Int): Query {
    val s = RunnersSessionsTable
    return s.select(s.columns)
        .where {
            (s.orgId eq orgId) and (s.hostDeviceId eq deviceId) and (s.phase eq RemoteSessionRules.WAITING_HOST) and
                (s.desiredState eq DesiredState.RUNNING.wire) and s.hostFolderId.isNotNull()
        }
        .orderBy(s.updatedAt to SortOrder.ASC, s.id to SortOrder.ASC)
        .limit(limit)
}

/** The desired state set, unless the session is over; the generation moves, as every write of the row. */
internal fun StatementBuilder.sessionDesired(
    orgId: Uuid,
    id: Uuid,
    desiredState: DesiredState,
    now: Instant,
): ReturningStatement {
    val s = RunnersSessionsTable
    return s.updateReturning(s.columns, where = { (s.id eq id) and (s.orgId eq orgId) and (s.state notInList OVER) }) {
        it[s.desiredState] = desiredState.wire
        it[generation] = s.generation + 1L
        it[updatedAt] = now
    }
}

/** Whose GitHub access the session uses, unless it is over; `none` only while it is paused. */
internal fun StatementBuilder.sessionGitHubAccess(
    orgId: Uuid,
    id: Uuid,
    access: GitHubAccessMode,
    now: Instant,
): ReturningStatement {
    val s = RunnersSessionsTable
    val allowed: Op<Boolean> = if (access == GitHubAccessMode.NONE) s.state eq PAUSED else Op.TRUE
    val open = (s.id eq id) and (s.orgId eq orgId) and (s.state notInList OVER)
    return s.updateReturning(s.columns, where = { open and allowed }) {
        it[githubAccess] = access.wire
        it[generation] = s.generation + 1L
        it[updatedAt] = now
    }
}

/** A session brought back to running, unless it is deleted, over or changing owner. */
internal fun StatementBuilder.sessionResumed(orgId: Uuid, id: Uuid, now: Instant): ReturningStatement {
    val s = RunnersSessionsTable
    return s.updateReturning(
        s.columns,
        where = {
            (s.id eq id) and (s.orgId eq orgId) and (s.desiredState neq DesiredState.TERMINATED.wire) and
                (s.state notInList OVER + TRANSFERRING) and s.transferId.isNull()
        },
    ) {
        it[desiredState] = DesiredState.RUNNING.wire
        it[generation] = s.generation + 1L
        it[updatedAt] = now
    }
}

/** A session written if nobody changed it since it was read; the generation moves. */
internal fun StatementBuilder.sessionStored(session: CloudSession): ReturningStatement {
    val s = RunnersSessionsTable
    return s.updateReturning(s.columns, where = { (s.id eq session.id) and (s.generation eq session.generation) }) {
        it[state] = session.state.wire
        it[desiredState] = session.desiredState.wire
        it[phase] = session.phase
        it[epoch] = session.epoch
        it[generation] = s.generation + 1L
        it[failureCode] = session.failure?.code
        it[failureMessage] = session.failure?.message
        it[failureRetryable] = session.failure?.retryable ?: false
        it[updatedAt] = session.updatedAt
        it[executionStartedAt] = session.executionStartedAt
        it[readyAt] = session.readyAt
        it[lastHeartbeatAt] = session.lastHeartbeatAt
        it[lastActivityAt] = greatest(s.lastActivityAt, session.lastActivityAt)
        it[pausedAt] = session.pausedAt
        it[terminatedAt] = session.terminatedAt
        it[pausingSince] = session.pausingSince
        it[runnerStoppedAt] = session.runnerStoppedAt
        it[runnerTokenHash] = session.runnerTokenHash
        it[backgroundSince] = session.backgroundSince
        it[backgroundSeenAt] = session.backgroundSeenAt
        it[profile] = session.profile?.let { spec -> RECIPE_JSON.encodeToString(SessionProfile.serializer(), spec) }
        it[clusterUnreachableSince] = session.clusterUnreachableSince
        it[ownerId] = session.ownerId
        it[transferId] = session.transferId
        it[untrustedWorkspace] = session.untrustedWorkspace
        it[githubAccess] = session.githubAccess.wire
        it[expiresAt] = session.expiresAt
        it[terminatedReason] = session.terminatedReason
        it[expiryBackfill] = session.expiryBackfill
        it.deletion(session.workspaceDeletion)
    }
}

/** The route of a written session: its runner token, and the reconciler's next look at the earliest asked. */
internal fun StatementBuilder.sessionRouted(session: CloudSession, nextReconcileAt: Instant?): UpdateStatement {
    val r = RunnersSessionRoutes
    return r.update({ r.sessionId eq session.id }) {
        it[runnerTokenHash] = session.runnerTokenHash
        if (nextReconcileAt != null) it[r.nextReconcileAt] = least(r.nextReconcileAt, nextReconcileAt)
    }
}

/** A request for a look at a session at [now] (an API write). */
internal fun StatementBuilder.sessionLookAt(id: Uuid, now: Instant): UpdateStatement {
    val r = RunnersSessionRoutes
    return r.update({ r.sessionId eq id }) { it[nextReconcileAt] = now }
}

/** The last activity moved forward, never backward. */
internal fun StatementBuilder.sessionTouched(id: Uuid, at: Instant): UpdateStatement {
    val s = RunnersSessionsTable
    return s.update({ s.id eq id }) { it[lastActivityAt] = greatest(s.lastActivityAt, at) }
}

/** A remote session asked to terminate with its device, unless it is over or ending already. */
internal fun StatementBuilder.sessionDeviceEnded(id: Uuid, deviceId: Uuid, now: Instant): UpdateStatement {
    val s = RunnersSessionsTable
    return s.update(
        {
            (s.id eq id) and (s.hostDeviceId eq deviceId) and (s.state notInList OVER) and
                (s.desiredState neq DesiredState.TERMINATED.wire)
        },
    ) {
        it[desiredState] = DesiredState.TERMINATED.wire
        it[terminatedReason] = RemoteSessionRules.DEVICE_REMOVED
        it[generation] = s.generation + 1L
        it[updatedAt] = now
    }
}

/** A session that lost its device: it can never run again. */
internal fun StatementBuilder.sessionDeviceForgotten(id: Uuid, deviceId: Uuid): UpdateStatement {
    val s = RunnersSessionsTable
    return s.update({ (s.id eq id) and (s.hostDeviceId eq deviceId) }) {
        it[hostDeviceId] = null
        it[generation] = s.generation + 1L
    }
}

/** Execution seconds added to an organization's month. */
internal fun StatementBuilder.usageAdded(orgId: Uuid, month: String, seconds: Long): UpsertStatement<Long> {
    val u = RunnersUsage
    return u.upsert(u.orgId, u.month, onUpdate = { it[u.seconds] = u.seconds + insertValue(u.seconds) }) {
        it[this.orgId] = orgId
        it[this.month] = month
        it[this.seconds] = seconds
    }
}
