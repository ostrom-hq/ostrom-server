// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A clock the test moves by hand. */
class SettableClock(var now: Instant = Instant.parse("2026-10-30T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** The organization and member most tests act for. */
val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")

/** A session in a given state, for reconciler and heartbeat tests. */
@Suppress("LongParameterList") // Every field a test may set, each with a default.
fun session(
    state: SessionState = SessionState.CREATING,
    desired: DesiredState = DesiredState.RUNNING,
    orgId: Uuid = ORG_A,
    ownerId: String = "alice",
    createdAt: Instant = Instant.parse("2026-10-30T10:00:00Z"),
    limits: SessionLimits = CloudPlan.FREE.sessionLimits(),
) = CloudSession(
    id = Uuid.random(),
    orgId = orgId,
    ownerId = ownerId,
    name = "task",
    agent = AgentKind.CLAUDE,
    repository = null,
    state = state,
    desiredState = desired,
    phase = null,
    epoch = 0,
    generation = 0,
    failure = null,
    limits = limits,
    createdAt = createdAt,
    updatedAt = createdAt,
)

/** Plans fixed by the test. */
class FixedPlans(var plan: CloudPlan = CloudPlan.FREE) : CloudPlanProvider {
    override suspend fun planFor(orgId: Uuid): CloudPlan = plan
}

/** Predictable tokens: `token-1`, `token-2`…, hashed as `hash(<token>)`. */
class FakeRunnerTokens : RunnerTokens {
    private var count = 0
    val minted = mutableListOf<String>()

    override fun mint(): String = "token-${++count}".also { minted += it }

    override fun hash(token: String): String = "hash($token)"
}

/** A runtime that records calls and answers what the test sets. */
class FakeRuntimeDriver : RuntimeDriver {
    override var relocates = false
    val calls = mutableListOf<String>()
    val started = mutableListOf<ExecutionSpec>()
    var status: ExecutionStatus = ExecutionStatus.PENDING
    var workspaceDeleted = true
    var failStart: Exception? = null
    var holder: Uuid? = null

    override suspend fun ensureWorkspace(session: CloudSession) {
        calls += "ensureWorkspace"
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        calls += "start:${spec.epoch}"
        failStart?.let { throw it }
        started += spec
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        calls += "status"
        return status
    }

    override suspend fun stopExecutions(session: CloudSession) {
        calls += "stop"
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        calls += "deleteWorkspace"
        return workspaceDeleted
    }

    override suspend fun holder(session: CloudSession): Uuid? = holder
}

/**
 * Sessions in memory, with the same rules as PostgreSQL: compare-and-set on the generation, monotonic activity,
 * the running cap.
 */
@Suppress("ReturnCount") // The fake mirrors the SQL guards one early return at a time.
class InMemoryCloudSessionsRepository : CloudSessionsRepository {
    val sessions = linkedMapOf<Uuid, CloudSession>()
    val next = mutableMapOf<Uuid, Instant?>()
    val usage = mutableMapOf<Pair<Uuid, String>, Long>()
    val released = mutableListOf<Pair<Uuid, Instant?>>()

    /** Makes the next compare-and-set lose its race, as if another writer came first. */
    var loseNextRaces = 0

    fun put(session: CloudSession) {
        sessions[session.id] = session
    }

    override suspend fun createWithinCap(session: CloudSession, maxRunning: Int): Boolean {
        if (holding(session.orgId, null) >= maxRunning) return false
        sessions[session.id] = session
        next[session.id] = session.createdAt
        return true
    }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? = sessions[id]?.takeIf { it.orgId == orgId }

    override suspend fun getById(id: Uuid): CloudSession? = sessions[id]

    override suspend fun list(orgId: Uuid, ownerId: String, before: Uuid?, limit: Int): List<CloudSession> {
        val all = sessions.values.filter { it.orgId == orgId && it.ownerId == ownerId }
            .sortedWith(compareByDescending<CloudSession> { it.createdAt }.thenByDescending { it.id.toString() })
        // None after a session it does not hold, as the database.
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return if (before != null && start == 0) emptyList() else all.drop(start).take(limit)
    }

    override suspend fun executions(orgId: Uuid, ownerId: String, limit: Int): List<CloudSession> =
        list(orgId, ownerId, null, Int.MAX_VALUE).filter { it.state.holdsExecution }.take(limit)

    override suspend fun setDesiredState(
        orgId: Uuid,
        id: Uuid,
        desiredState: DesiredState,
        now: Instant,
    ): CloudSession? {
        val current = get(orgId, id) ?: return null
        if (current.state.isFinal) return current
        val updated = current.copy(desiredState = desiredState, generation = current.generation + 1, updatedAt = now)
        sessions[id] = updated
        next[id] = now
        return updated
    }

    override suspend fun setGitHubAccess(
        orgId: Uuid,
        id: Uuid,
        access: GitHubAccessMode,
        now: Instant,
    ): CloudSession? {
        val current = get(orgId, id)?.takeUnless { it.state.isFinal } ?: return null
        if (access == GitHubAccessMode.NONE && current.state != SessionState.PAUSED) return null
        val updated = current.copy(githubAccess = access, generation = current.generation + 1, updatedAt = now)
        sessions[id] = updated
        return updated
    }

    override suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: Instant): ResumeOutcome {
        val current = get(orgId, id) ?: return ResumeOutcome.NotFound
        if (!current.state.holdsExecution && holding(orgId, id) >= maxRunning) return ResumeOutcome.AtCap
        val updated = current.copy(
            desiredState = DesiredState.RUNNING,
            generation = current.generation + 1,
            updatedAt = now,
        )
        sessions[id] = updated
        next[id] = now
        return ResumeOutcome.Resumed(updated)
    }

    override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? {
        val current = sessions[session.id] ?: return null
        if (loseNextRaces > 0) {
            loseNextRaces--
            sessions[session.id] = current.copy(generation = current.generation + 1)
            return null
        }
        if (current.generation != session.generation) return null
        val activity = listOfNotNull(current.lastActivityAt, session.lastActivityAt).maxOrNull()
        val stored = session.copy(generation = session.generation + 1, lastActivityAt = activity)
        sessions[session.id] = stored
        if (nextReconcileAt != null) next[session.id] = listOfNotNull(next[session.id], nextReconcileAt).min()
        return stored
    }

    override suspend fun touchActivity(id: Uuid, at: Instant) {
        val current = sessions[id] ?: return
        sessions[id] = current.copy(lastActivityAt = listOfNotNull(current.lastActivityAt, at).max())
    }

    override suspend fun findByRunnerTokenHash(tokenHash: String): CloudSession? =
        sessions.values.firstOrNull { it.runnerTokenHash == tokenHash }

    override suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession> =
        sessions.values.filter { s -> next[s.id]?.let { it <= now } == true }.take(limit)

    override suspend fun release(id: Uuid, owner: String, nextReconcileAt: Instant?) {
        released += id to nextReconcileAt
        next[id] = nextReconcileAt
    }

    override suspend fun usageSeconds(orgId: Uuid, month: String): Long = usage[orgId to month] ?: 0

    override suspend fun addUsage(orgId: Uuid, month: String, seconds: Long) {
        usage[orgId to month] = usageSeconds(orgId, month) + seconds
    }

    private fun holding(orgId: Uuid, except: Uuid?): Int =
        sessions.values.count { it.orgId == orgId && it.id != except && it.state.holdsExecution }
}
