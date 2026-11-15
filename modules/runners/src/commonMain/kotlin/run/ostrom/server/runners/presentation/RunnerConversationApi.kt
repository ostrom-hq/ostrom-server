// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The agent channel of the runner (`runners.cloud.conversation.v1`, TeamCode's `/cloud/runner/agent|events|inputs`),
// authenticated like the lifecycle channel by the execution's runner token.

/** `POST /api/v1/cloud/runner/agent` (`cloudRunnerAgent`): how the runner starts its agent. */
@Serializable
@Resource("/api/v1/cloud/runner/agent")
class RunnerAgentApi

/** `POST /api/v1/cloud/runner/events` (`cloudRunnerEvents`): a batch of the agent's transcript events. */
@Serializable
@Resource("/api/v1/cloud/runner/events")
class RunnerEventsApi

/** `POST /api/v1/cloud/runner/inputs` (`cloudRunnerInputs`): the long-poll of the session's inputs. */
@Serializable
@Resource("/api/v1/cloud/runner/inputs")
class RunnerInputsApi {
    /**
     * `POST /api/v1/cloud/runner/inputs/ack` (`cloudRunnerInputsAck`): what became of the inputs handed.
     *
     * @property parent The inputs.
     */
    @Serializable
    @Resource("ack")
    class Ack(val parent: RunnerInputsApi = RunnerInputsApi())
}

/**
 * The execution a runner call names.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 */
@Serializable
data class RunnerAgentRequest(val sessionId: String, val epoch: Long)

/**
 * The member an execution runs for, as the agent is told; never an email.
 *
 * @property username Their login.
 * @property githubLogin Their linked GitHub account's login.
 */
@Serializable
data class RunnerOwnerDto(val username: String, val githubLogin: String? = null)

/**
 * How the runner starts its agent.
 *
 * @property kind `claude` or `codex`.
 * @property model The model, if chosen.
 * @property effort The effort, if chosen.
 * @property conversation `resumable`, `transcript_only` or `none`.
 * @property permissionRelay The server takes `permission.request` events.
 * @property permissionDeadlineMinutes How long a request waits before the runner denies it; 0 waits without one.
 * @property liveText The server takes `message.agent.delta` events.
 * @property backgroundTasks The server takes `tasks` events.
 * @property owner The member the execution runs for.
 */
@Serializable
data class RunnerAgentResponse(
    val kind: String,
    val model: String? = null,
    val effort: String? = null,
    val conversation: String,
    val permissionRelay: Boolean,
    val permissionDeadlineMinutes: Int,
    val liveText: Boolean,
    val backgroundTasks: Boolean,
    val owner: RunnerOwnerDto? = null,
)

/**
 * One event as the runner sends it.
 *
 * @property sequence The runner's own consecutive counter in its epoch, from 1.
 * @property type The event type.
 * @property data The event's fields; the server bounds and masks them again.
 */
@Serializable
data class RunnerEventDto(val sequence: Long, val type: String, val data: JsonObject)

/**
 * A batch of events.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property events At most 200 events, in the runner's order.
 */
@Serializable
data class RunnerEventsRequest(val sessionId: String, val epoch: Long, val events: List<RunnerEventDto>)

/**
 * What a batch did.
 *
 * @property acceptedThrough The runner's last sequence stored: it resends from the next one.
 * @property lastSeq The session's newest event.
 */
@Serializable
data class RunnerEventsResponse(val acceptedThrough: Long, val lastSeq: Long)

/**
 * A long-poll of the inputs.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property waitMillis How long to wait for inputs, at most 20,000.
 * @property desiredStatus The desired state the runner last knew: the poll ends as soon as the session's differs.
 * @property acceptsFiles The runner downloads a message's files: without it, a message with files is rejected for it
 *   (`files_unsupported`).
 * @property acceptsTaskStops The runner stops background tasks.
 * @property acceptsModelChanges The runner changes its agent's model.
 * @property acceptsEffortChanges The runner changes its agent's effort.
 */
@Serializable
data class RunnerInputsRequest(
    val sessionId: String,
    val epoch: Long,
    val waitMillis: Long = 0,
    val desiredStatus: String? = null,
    val acceptsFiles: Boolean = false,
    val acceptsTaskStops: Boolean = false,
    val acceptsModelChanges: Boolean = false,
    val acceptsEffortChanges: Boolean = false,
)

/**
 * An input handed to the runner.
 *
 * @property id The input: the runner acknowledges it, and drops a repeat.
 * @property kind `message`, `interrupt`, `permission`, `stop_task`, `set_model` or `set_effort`.
 * @property text A message's text; a permission answer's JSON; a task's id; a model or effort.
 * @property attempt How many times it was handed, this one included.
 * @property files The files a message carries (`runners.cloud.files.v1`): downloaded before the agent reads it.
 */
@Serializable
data class RunnerInputDto(
    val id: String,
    val kind: String,
    val text: String? = null,
    val attempt: Int,
    val files: List<RunnerFileDto> = emptyList(),
)

/**
 * The inputs handed.
 *
 * @property inputs The inputs, now `delivered` to this execution.
 * @property desiredStatus The session's desired state when the poll ended.
 */
@Serializable
data class RunnerInputsResponse(val inputs: List<RunnerInputDto>, val desiredStatus: String)

/**
 * The runner's outcome for an input.
 *
 * @property id The input.
 * @property status `consumed` or `rejected`.
 * @property reason Why it was rejected, a stable code.
 */
@Serializable
data class RunnerInputAckDto(val id: String, val status: String, val reason: String? = null)

/**
 * The outcomes of inputs handed.
 *
 * @property sessionId The session.
 * @property epoch The execution.
 * @property acks At most 100 outcomes.
 */
@Serializable
data class RunnerInputsAckRequest(val sessionId: String, val epoch: Long, val acks: List<RunnerInputAckDto>)
