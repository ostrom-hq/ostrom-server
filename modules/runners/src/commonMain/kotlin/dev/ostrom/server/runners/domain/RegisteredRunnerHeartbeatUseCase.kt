// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A registered runner's sign of life (the contract's `runnerHeartbeat`), authenticated by its key: it marks the runner
 * seen, which shows it `online` for [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS], records what it reports, and
 * returns the work assigned to it.
 */
interface RegisteredRunnerHeartbeatUseCase {
    /**
     * Records it.
     *
     * @param id The runner the request names.
     * @param key The key it presented.
     * @param report What the runner runs and keeps; null from a runner that does not run sessions, which is then given
     *   none.
     *
     * @return What it must hold: the executions to run, each plan handed over once, and the workspaces to keep.
     *
     * @throws RunnerUnauthenticatedException When no runner has that id or the key is not its own; both answer the
     *   same, so neither the runner's existence nor its organization leaks.
     */
    suspend operator fun invoke(id: Uuid, key: String, report: MachineReport? = null): MachineWork
}

/**
 * [RegisteredRunnerHeartbeatUseCase] on the repositories. The key is hashed and compared in constant time whether the
 * runner exists or not (against a hash no key has when it does not), so an unknown runner and a wrong key cost the same
 * single read; the sign of life is then written only if the runner still has that key in its organization.
 *
 * An execution is handed over with a runner token minted then: the reconciler's own is never stored in clear, so the
 * handover replaces its hash on the session (a compare-and-set, retried at the next heartbeat when it loses a race).
 *
 * @param repository The runners.
 * @param tokens Hashes the key, mints the runner tokens.
 * @param clock The time.
 * @param assignments The executions handed to runners.
 * @param sessions The sessions.
 * @param planner What an execution is on the runner's Docker Engine.
 */
class RegisteredRunnerHeartbeatUseCaseImpl(
    private val repository: RegisteredRunnersRepository,
    private val tokens: RunnerTokens,
    private val clock: Clock,
    private val assignments: RunnerAssignmentsRepository,
    private val sessions: CloudSessionsRepository,
    private val planner: ExecutionPlanner,
) : RegisteredRunnerHeartbeatUseCase {
    override suspend fun invoke(id: Uuid, key: String, report: MachineReport?): MachineWork {
        val hash = tokens.hash(key)
        val stored = repository.keyOf(id)
        val matches = constantTimeEquals(hash, if (stored == null) NO_KEY_HASH else stored.keyHash)
        val now = clock.now()
        if (stored == null || !matches || !repository.touch(stored.orgId, id, hash, now)) {
            throw RunnerUnauthenticatedException()
        }
        if (report == null) return MachineWork(emptyList(), emptyList())
        val held = assignments.exchange(stored.orgId, id, report)
        // An execution not handed over yet is left out until its handover succeeds.
        val executions = held.filter { !it.stopped }.mapNotNull { assignment ->
            when {
                assignment.delivered -> AssignedExecution(assignment.sessionId, assignment.epoch, null)
                else -> handOver(stored.orgId, assignment, now)
                    ?.let { AssignedExecution(assignment.sessionId, assignment.epoch, it) }
            }
        }
        val workspaces = held.filter { !it.dropped }.map { it.sessionId }.distinct()
        return MachineWork(executions, workspaces)
    }

    /** The plan of an execution not handed over yet, with a fresh runner token; null while it cannot be handed over. */
    private suspend fun handOver(orgId: Uuid, assignment: Assignment, now: Instant): ExecutionPlan? {
        val session = sessions.get(orgId, assignment.sessionId)
            ?.takeIf { it.epoch == assignment.epoch && it.state in STARTING }
            ?: return null
        val token = tokens.mint()
        return sessions.compareAndSet(session.copy(runnerTokenHash = tokens.hash(token)), null)?.let { minted ->
            assignments.delivered(orgId, assignment.sessionId, assignment.epoch, now)
            planner.plan(minted.executionSpec(token))
        }
    }
}

/** The states in which an execution waits for its runner. */
private val STARTING = setOf(SessionState.CREATING, SessionState.RESUMING)

/** Compared with when no runner has the id: a SHA-256 hex digest no key hashes to in practice. */
private const val NO_KEY_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * Compares two strings in a time that depends on their length only, never on where they first differ (the lengths of
 * two SHA-256 hex digests are public).
 *
 * @param a One string.
 * @param b The other.
 *
 * @return True when they are equal.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
