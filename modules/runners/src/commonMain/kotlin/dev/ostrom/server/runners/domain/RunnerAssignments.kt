// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The executions of cloud sessions handed to registered runners (`ostrom-runner machine`), one row per execution:
 * waiting for a runner, taken by one, delivered, stopped, and dropped with its workspace. Every call is confined to
 * one organization: a runner only ever takes its own organization's sessions.
 */
interface RunnerAssignmentsRepository {
    /**
     * Records an execution to run, unless it is recorded already. It goes back to the runner of the session's latest
     * execution while that runner is online (its workspace is there), and to whichever runner asks first otherwise.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param epoch The execution.
     * @param now The time.
     * @param onlineSince A runner seen at or after this time is online.
     */
    suspend fun assign(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant, onlineSince: Instant)

    /**
     * Reads an execution's assignment.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param epoch The execution.
     *
     * @return It, or null when the execution was never assigned or its session's workspace was dropped.
     */
    suspend fun find(orgId: Uuid, sessionId: Uuid, epoch: Long): Assignment?

    /**
     * Asks the runners to stop every execution of a session; their workspace stays.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param now The time.
     */
    suspend fun stop(orgId: Uuid, sessionId: Uuid, now: Instant)

    /**
     * Asks the runners to stop a session's executions and to delete its workspace, then forgets the session once no
     * online runner still holds the workspace.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param now The time.
     * @param onlineSince A runner seen at or after this time is online.
     *
     * @return True once the session is forgotten, false while an online runner still holds its workspace.
     */
    suspend fun drop(orgId: Uuid, sessionId: Uuid, now: Instant, onlineSince: Instant): Boolean

    /**
     * Records what a runner reports, lets it take one waiting execution, and reads what it holds.
     *
     * @param orgId The runner's organization.
     * @param runnerId The runner.
     * @param report What it runs and keeps.
     *
     * @return The executions it holds, taken one included.
     */
    suspend fun exchange(orgId: Uuid, runnerId: Uuid, report: MachineReport): List<Assignment>

    /**
     * Records that an execution's plan was handed to its runner.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param epoch The execution.
     * @param now The time.
     */
    suspend fun delivered(orgId: Uuid, sessionId: Uuid, epoch: Long, now: Instant)
}

/**
 * One execution handed to registered runners.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property runnerId The runner that took it, or null while it waits.
 * @property runnerSeenAt That runner's last heartbeat, or null (never seen, or removed).
 * @property state What the runner last reported, or null before its first report.
 * @property delivered Whether its plan was handed to the runner.
 * @property stopped Whether it must stop.
 * @property dropped Whether its session's workspace must go.
 * @property superseded Whether another runner took a later execution of its session: the workspace this runner holds
 *   is then stale, and it deletes it (a machine back after it was declared lost never keeps the session's data).
 */
data class Assignment(
    val sessionId: Uuid,
    val epoch: Long,
    val runnerId: Uuid?,
    val runnerSeenAt: Instant?,
    val state: MachineExecutionState?,
    val delivered: Boolean,
    val stopped: Boolean,
    val dropped: Boolean,
    val superseded: Boolean = false,
)

/** What a registered runner reports of one execution, from its runner container. */
enum class MachineExecutionState(val wire: String) {
    /** Created, not running yet. */
    PENDING("pending"),

    /** Running. */
    RUNNING("running"),

    /** Exited, whatever the code. */
    ENDED("ended"),

    /** Handed to the runner, but the runner does not have it (never created, or gone). */
    MISSING("missing"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire The value.
         *
         * @return The state, or null when unknown.
         */
        fun of(wire: String): MachineExecutionState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a registered runner reports at its heartbeat.
 *
 * @property executions The executions it has, by session and epoch.
 * @property workspaces The sessions whose workspace it holds.
 */
data class MachineReport(
    val executions: Map<Pair<Uuid, Long>, MachineExecutionState> = emptyMap(),
    val workspaces: Set<Uuid> = emptySet(),
)

/**
 * What a registered runner must hold after its heartbeat: anything else of its own it stops and deletes.
 *
 * @property executions The executions to run; the plan comes once, when it is handed over.
 * @property workspaces The sessions whose workspace to keep.
 */
data class MachineWork(val executions: List<AssignedExecution>, val workspaces: List<Uuid>)

/**
 * One execution a registered runner must run.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property plan What to create, at the handover only; null once handed over.
 */
data class AssignedExecution(val sessionId: Uuid, val epoch: Long, val plan: ExecutionPlan?)

/**
 * What an execution is on a Docker Engine: the session's volumes and the execution's containers, in creation order.
 *
 * @property volumes The volumes.
 * @property containers The containers; the runner's, last, holds the execution's secrets.
 */
data class ExecutionPlan(val volumes: List<PlannedObject>, val containers: List<PlannedObject>)

/**
 * A volume or a container to create on a Docker Engine.
 *
 * @property name Its name.
 * @property body The Engine API's creation body.
 */
data class PlannedObject(val name: String, val body: JsonObject) {
    /** The body may hold the runner's token: never printed. */
    override fun toString(): String = "PlannedObject(name=$name)"
}

/** Computes what an execution is on a Docker Engine, with its secrets. */
fun interface ExecutionPlanner {
    /**
     * Plans an execution.
     *
     * @param spec The execution.
     *
     * @return Its plan.
     */
    fun plan(spec: ExecutionSpec): ExecutionPlan
}
