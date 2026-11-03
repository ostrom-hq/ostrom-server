// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/v1/runners` (ostrom-core's operation `listRunners`): the organization's registered runners, then the
 * runners at work for the caller; `POST` registers one (`registerRunner`).
 */
@Serializable
@Resource("/api/v1/runners")
class RunnersApi {
    /**
     * `DELETE /api/v1/runners/{id}` (`deleteRunner`): removes a registered runner.
     *
     * @property parent The collection.
     * @property id The runner.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: RunnersApi = RunnersApi(), val id: String) {
        /**
         * `POST /api/v1/runners/{id}/heartbeat` (`runnerHeartbeat`): a registered runner's sign of life, with its key.
         *
         * @property parent The runner.
         */
        @Serializable
        @Resource("heartbeat")
        class Heartbeat(val parent: Id)
    }
}

/**
 * What a registered runner reports at its heartbeat, as the contract's; a runner that runs no session sends no body
 * and is given none.
 *
 * @property executions The executions it has.
 * @property workspaces The sessions whose workspace it holds.
 * @property capacity The sessions it runs at once at most (0 to 256; 0 drains the machine); none for no limit.
 */
@Serializable
data class RegisteredRunnerHeartbeatRequest(
    val executions: List<ReportedExecutionDto> = emptyList(),
    val workspaces: List<String> = emptyList(),
    val capacity: Int? = null,
)

/**
 * One execution a registered runner has.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property state `pending`, `running` or `ended`: its runner container's state.
 */
@Serializable
data class ReportedExecutionDto(val sessionId: String, val epoch: Long, val state: String)

/**
 * The answer of a registered runner's heartbeat, as the contract's: what it must hold. Anything else of its own, it
 * stops and deletes.
 *
 * @property sessions The cloud sessions assigned to it: always empty, its executions are in [executions].
 * @property executions The executions to run.
 * @property workspaces The sessions whose workspace to keep.
 */
@Serializable
data class RegisteredRunnerHeartbeatResponse(
    val sessions: List<CloudSessionResponse>,
    val executions: List<AssignedExecutionDto>,
    val workspaces: List<String>,
)

/**
 * One execution a registered runner must run.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property plan What to create on its Docker Engine, given once, at the handover.
 */
@Serializable
data class AssignedExecutionDto(val sessionId: String, val epoch: Long, val plan: ExecutionPlanDto? = null)

/**
 * The Docker Engine objects of an execution, in creation order.
 *
 * @property volumes The session's volumes.
 * @property containers The execution's containers; the last, the runner's, holds the runner token.
 */
@Serializable
data class ExecutionPlanDto(val volumes: List<PlannedObjectDto>, val containers: List<PlannedObjectDto>)

/**
 * A volume or a container: its name and the Engine API's creation body.
 *
 * @property name Its name.
 * @property body The creation body.
 */
@Serializable
data class PlannedObjectDto(val name: String, val body: JsonObject) {
    /** The body may hold the runner token: never printed. */
    override fun toString(): String = "PlannedObjectDto(name=$name)"
}

/**
 * A runner, as the contract's `Runner`.
 *
 * @property id The id of the cloud session it runs: one runner per session execution.
 * @property name The session's name.
 * @property labels Where it runs (`kubernetes`, `docker`) and the agent it runs.
 * @property status `online` when it reported within the last 90 s, `offline` otherwise (starting, or lost).
 * @property lastSeenAt Its last heartbeat (ISO 8601), absent before the first one.
 * @property version The runner image's tag.
 */
@Serializable
data class RunnerDto(
    val id: String,
    val name: String,
    val labels: List<String>,
    val status: String,
    val lastSeenAt: String? = null,
    val version: String? = null,
)

/**
 * The runners, as the contract's page; never cut, so without `next`.
 *
 * @property items The runners.
 */
@Serializable
data class RunnersPage(val items: List<RunnerDto>)

/**
 * The body of `POST /api/v1/runners` (ostrom-core's operation `registerRunner`).
 *
 * @property name The runner's name, unique in the organization.
 * @property labels What it offers.
 * @property version The version it declares.
 */
@Serializable
data class RegisterRunnerRequest(
    val name: String,
    val labels: List<String>,
    val version: String? = null,
)

/**
 * A runner just registered: the contract's `Runner`, plus its key, shown this once.
 *
 * @property id The runner.
 * @property name Its name.
 * @property labels What it offers.
 * @property status `offline` until its first sign of life.
 * @property lastSeenAt Its last sign of life, absent before the first one.
 * @property version The version it declared.
 * @property token Its key, `ostrom_runner_` and 64 hex characters: returned only here, stored only as a hash.
 */
@Serializable
data class RegisteredRunnerDto(
    val id: String,
    val name: String,
    val labels: List<String>,
    val status: String,
    val lastSeenAt: String? = null,
    val version: String? = null,
    val token: String,
) {
    override fun toString(): String = "RegisteredRunnerDto(id=$id, name=$name, token=***)"
}
