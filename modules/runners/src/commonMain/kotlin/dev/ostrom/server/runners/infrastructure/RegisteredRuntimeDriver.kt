// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.ExecutionSpec
import dev.ostrom.server.runners.domain.ExecutionStatus
import dev.ostrom.server.runners.domain.MachineExecutionState
import dev.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerAssignmentsRepository
import dev.ostrom.server.runners.domain.RuntimeDriver
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [RuntimeDriver] on the organization's registered runners (`ostrom-runner machine`): the server creates nothing
 * itself. It assigns each execution to an online runner of the session's organization, which takes it at its next
 * heartbeat and creates it on its own Docker Engine from the plan the server computes ([DockerExecutionPlan]); the
 * runner then reports its state at every heartbeat.
 *
 * A runner silent for [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS] is lost with its executions, which therefore
 * [relocate][relocates]: the reconciler starts a new execution, which another runner takes. The workspace was on the
 * dead machine: the new one starts from a fresh clone.
 *
 * @param assignments The executions handed to runners.
 * @param clock The time.
 */
class RegisteredRuntimeDriver(
    private val assignments: RunnerAssignmentsRepository,
    private val clock: Clock,
) : RuntimeDriver {
    override val relocates: Boolean = true

    // The runner creates the volumes with the execution.
    override suspend fun ensureWorkspace(session: CloudSession) = Unit

    // The reconciler's token is not kept: the heartbeat that hands the execution over mints the runner's.
    override suspend fun startExecution(spec: ExecutionSpec) {
        val now = clock.now()
        assignments.assign(spec.orgId, spec.sessionId, spec.epoch, now, onlineSince(now))
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        val assignment = assignments.find(session.orgId, session.id, session.epoch)
        val seenAt = assignment?.runnerSeenAt
        return when {
            assignment == null -> ExecutionStatus.MISSING
            assignment.runnerId == null -> ExecutionStatus.QUEUED
            seenAt == null || seenAt < onlineSince(clock.now()) -> ExecutionStatus.HOST_LOST
            // Pending, or not reported yet.
            else -> STATUS_OF[assignment.state] ?: ExecutionStatus.PENDING
        }
    }

    override suspend fun stopExecutions(session: CloudSession) {
        assignments.stop(session.orgId, session.id, clock.now())
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val now = clock.now()
        return assignments.drop(session.orgId, session.id, now, onlineSince(now))
    }

    private fun onlineSince(now: Instant) = now - ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS

    private companion object {
        /**
         * What the reconciler reads from a machine's report. A map rather than a `when`: an exhaustive `when` on a
         * nullable enum compiles a default branch no test can reach.
         */
        private val STATUS_OF = mapOf(
            MachineExecutionState.RUNNING to ExecutionStatus.RUNNING,
            MachineExecutionState.ENDED to ExecutionStatus.ENDED,
            MachineExecutionState.MISSING to ExecutionStatus.ENDED,
        )
    }
}
