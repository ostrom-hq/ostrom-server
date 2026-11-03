// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.fakes

import dev.ostrom.server.runners.domain.Assignment
import dev.ostrom.server.runners.domain.MachineExecutionState
import dev.ostrom.server.runners.domain.MachineReport
import dev.ostrom.server.runners.domain.RunnerAssignmentsRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The executions handed to registered runners, in memory, with the same rules as PostgreSQL.
 *
 * @param seenAt A runner's last heartbeat, null when never seen or removed.
 * @param capacityOf The capacity a runner declared at its last heartbeat, null for no limit.
 */
class InMemoryRunnerAssignments(
    private val seenAt: (Uuid) -> Instant? = { null },
    private val capacityOf: (Uuid) -> Int? = { null },
) : RunnerAssignmentsRepository {
    /** One row of `runners_assignments`. */
    data class Row(
        val orgId: Uuid,
        val sessionId: Uuid,
        val epoch: Long,
        var runnerId: Uuid? = null,
        var state: MachineExecutionState? = null,
        var delivered: Boolean = false,
        var stopped: Boolean = false,
        var dropped: Boolean = false,
        var holdsWorkspace: Boolean = false,
    )

    val rows = mutableListOf<Row>()

    private fun online(runnerId: Uuid?, since: Instant) = runnerId?.let(seenAt)?.let { it >= since } == true

    private fun room(runnerId: Uuid?): Boolean {
        val capacity = runnerId?.let(capacityOf) ?: return true
        return rows.count { it.runnerId == runnerId && !it.stopped } < capacity
    }

    override suspend fun assign(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant, onlineSince: Instant) {
        if (rows.any { it.sessionId == sessionId && it.epoch == epoch }) return
        val previous = rows
            .filter { it.orgId == orgId && it.sessionId == sessionId }
            .filter { online(it.runnerId, onlineSince) && room(it.runnerId) }
            .maxByOrNull { it.epoch }
        rows += Row(orgId, sessionId, epoch, runnerId = previous?.runnerId)
    }

    override suspend fun find(orgId: Uuid, sessionId: Uuid, epoch: Long): Assignment? =
        rows.firstOrNull { it.orgId == orgId && it.sessionId == sessionId && it.epoch == epoch }?.toAssignment()

    override suspend fun stop(orgId: Uuid, sessionId: Uuid, now: Instant) {
        rows.filter { it.orgId == orgId && it.sessionId == sessionId }.forEach { it.stopped = true }
    }

    override suspend fun drop(orgId: Uuid, sessionId: Uuid, now: Instant, onlineSince: Instant): Boolean {
        val session = rows.filter { it.orgId == orgId && it.sessionId == sessionId }
        session.forEach {
            it.stopped = true
            it.dropped = true
        }
        if (session.any { it.holdsWorkspace && online(it.runnerId, onlineSince) }) return false
        rows.removeAll(session)
        return true
    }

    override suspend fun exchange(orgId: Uuid, runnerId: Uuid, report: MachineReport): List<Assignment> {
        for (row in rows.filter { it.orgId == orgId && it.runnerId == runnerId }) {
            val reported = report.executions[row.sessionId to row.epoch]
            row.state = reported ?: MachineExecutionState.MISSING.takeIf { row.delivered && !row.stopped } ?: row.state
            row.holdsWorkspace = row.sessionId in report.workspaces
        }
        val waiting = rows.firstOrNull { it.orgId == orgId && it.runnerId == null && !it.stopped }
        if (room(runnerId)) waiting?.runnerId = runnerId
        return rows.filter { it.orgId == orgId && it.runnerId == runnerId }.map { it.toAssignment() }
    }

    override suspend fun delivered(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant) {
        rows.filter { it.orgId == orgId && it.sessionId == sessionId && it.epoch == epoch }
            .forEach { it.delivered = true }
    }

    private fun Row.toAssignment() = Assignment(
        sessionId, epoch, runnerId, runnerId?.let(seenAt), state, delivered, stopped, dropped,
        superseded = rows.any { later ->
            val other = later.runnerId.let { it != null && it != runnerId }
            later.sessionId == sessionId && later.epoch > epoch && other
        },
    )
}
