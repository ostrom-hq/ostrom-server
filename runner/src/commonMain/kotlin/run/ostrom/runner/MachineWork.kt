// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What a registered runner reports at its heartbeat (the body of `POST /api/v1/runners/{id}/heartbeat`).
 *
 * @property executions The executions it has: its runner containers, and the plans it is creating.
 * @property workspaces The sessions whose workspace volumes it holds.
 * @property capacity The sessions it runs at once at most: the server never hands it more. Null (left out of the
 *   body) when unknown: no limit.
 */
@Serializable
data class MachineReport(val executions: List<ReportedExecution>, val workspaces: List<String>, val capacity: Int?)

/**
 * One execution a registered runner has.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property state `pending`, `running` or `ended`.
 */
@Serializable
data class ReportedExecution(val sessionId: String, val epoch: Long, val state: String)

/**
 * What the server answers a registered runner: what it must hold. Anything else of its own, it stops and deletes.
 *
 * @property executions The executions to run.
 * @property workspaces The sessions whose workspace to keep.
 */
@Serializable
data class MachineWork(
    val executions: List<AssignedExecution> = emptyList(),
    val workspaces: List<String> = emptyList(),
)

/**
 * One execution to run.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property plan What to create, given once, when the execution is handed over.
 */
@Serializable
data class AssignedExecution(val sessionId: String, val epoch: Long, val plan: ExecutionPlan? = null)

/**
 * The Docker Engine objects of an execution, in creation order.
 *
 * @property volumes The session's volumes.
 * @property containers The execution's containers; the last, the runner's, holds the runner token.
 */
@Serializable
data class ExecutionPlan(val volumes: List<PlannedObject>, val containers: List<PlannedObject>)

/**
 * A volume or a container: its name and the Engine API's creation body.
 *
 * @property name Its name.
 * @property body The creation body.
 */
@Serializable
data class PlannedObject(val name: String, val body: JsonObject) {
    /** The body may hold the runner token: never printed. */
    override fun toString(): String = "PlannedObject(name=$name)"
}
