// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.uuid.Uuid

// The agent side of the runner channel of a conversation session (TeamCode's RunnerConversationUseCase.kt and the
// runner models of CloudConversationModels.kt): what the runner starts, what it uploads, what it is handed.

/**
 * Whether the agent's conversation can be picked up again by the next execution (TeamCode's `ConversationResume`).
 *
 * @property wire The stable name, in the `status` event the runner sends and in the agent's configuration.
 */
enum class ConversationResume(val wire: String) {
    /** The agent's own conversation is kept in the workspace: the next execution resumes it. */
    RESUMABLE("resumable"),

    /** Only the transcript is kept: the next execution starts a fresh conversation. */
    TRANSCRIPT_ONLY("transcript_only"),

    /** Nothing to resume: a session that never ran its agent. */
    NONE("none"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param value A stored or sent name.
         * @return The value, or null when the name is unknown.
         */
        fun parse(value: String?): ConversationResume? = entries.singleOrNull { it.wire == value }
    }
}

/** What a `turn` event says about the agent: it took a turn, or it is idle again. */
enum class TurnMarker {
    /** A turn started. */
    STARTED,

    /** A turn completed, was interrupted or failed. */
    ENDED,
}

/**
 * One event of a runner batch, already bounded and masked.
 *
 * @property sequence The runner's own consecutive counter in its epoch, from 1.
 * @property type The event type, one the runner may send.
 * @property data The sanitized JSON object, encoded.
 * @property turn What a `turn` event says, if anything.
 */
data class RunnerEvent(
    val sequence: Long,
    val type: CloudEventType,
    val data: String,
    val turn: TurnMarker? = null,
)

/**
 * What a batch did.
 *
 * @property acceptedThrough The runner's last sequence stored: it resends from the next one.
 * @property lastSeq The session's newest event.
 */
data class RunnerEventsAck(val acceptedThrough: Long, val lastSeq: Long)

/**
 * The runner's outcome for an input it was handed.
 *
 * @property id The input.
 * @property consumed True when the agent took it; false when the runner refused it.
 * @property reason Why it was refused, a stable code.
 */
data class RunnerInputAck(val id: Uuid, val consumed: Boolean, val reason: String?)

/**
 * The member an execution runs for, as the agent is told.
 *
 * @property username Their login.
 * @property githubLogin Their linked GitHub account's login, if known.
 */
data class RunnerOwner(val username: String, val githubLogin: String?)

/**
 * How the runner starts its agent (TeamCode's `RunnerAgentConfig`).
 *
 * @property kind The agent.
 * @property model The model, if chosen.
 * @property effort The effort, if chosen.
 * @property conversation Whether the conversation can be resumed.
 * @property permissionRelay The runner relays the agent's permission requests (Claude Code only).
 * @property permissionDeadlineMinutes How long a request waits before the runner denies it; 0 for no deadline.
 * @property liveText The runner streams the agent's text as `message.agent.delta` events (Claude Code only).
 * @property backgroundTasks The runner reports the agent's background tasks as `tasks` events (Claude Code only).
 * @property owner The member the execution runs for, if they are still known.
 */
data class RunnerAgentConfig(
    val kind: AgentKind,
    val model: String?,
    val effort: String?,
    val conversation: ConversationResume,
    val permissionRelay: Boolean,
    val permissionDeadlineMinutes: Int,
    val liveText: Boolean,
    val backgroundTasks: Boolean,
    val owner: RunnerOwner?,
)

/**
 * The input kinds a runner takes besides messages, interrupts and permission answers: a kind it does not announce is
 * refused for it with a reason instead of being delivered. Messages carry no files on this server yet.
 *
 * @property taskStops Stops of background tasks.
 * @property modelChanges Model changes.
 * @property effortChanges Effort changes.
 */
data class RunnerAccepts(
    val taskStops: Boolean = false,
    val modelChanges: Boolean = false,
    val effortChanges: Boolean = false,
)

/**
 * Inputs handed to the runner, and the session's desired state when they were read.
 *
 * @property inputs The inputs, now `delivered` to this execution.
 * @property desiredState The session's desired state.
 */
data class RunnerInputs(val inputs: List<CloudInput>, val desiredState: DesiredState)

/**
 * The execution a runner call is for, once [AuthenticateRunnerUseCase] checked its credential: the fence of every call
 * of the agent channel.
 *
 * @property orgId The organization.
 * @property sessionId The session.
 * @property epoch The execution.
 */
data class RunnerExecution(val orgId: Uuid, val sessionId: Uuid, val epoch: Long)

/**
 * The runner side of the conversation's tables (TeamCode's `appendRunnerEvents`, `deliver` and `acknowledge`). Every
 * write locks the session row first, so the transcript's numbering and the inputs' statuses agree across replicas.
 */
interface RunnerConversationStore {
    /**
     * Stores a batch in the runner's order, skipping what an earlier try stored and stopping at a gap. The wait the
     * session shows follows the batch: a permission request of a running session opens it, the end of a turn closes
     * it, an expiry moves it to the next request still open; the subagents follow their calls, results and tasks.
     *
     * @return What was stored.
     * @throws CloudSessionNotFoundException When the session is gone.
     */
    suspend fun appendRunnerEvents(
        orgId: Uuid,
        sessionId: Uuid,
        epoch: Long,
        events: List<RunnerEvent>,
    ): RunnerEventsAck

    /**
     * Hands this execution its next inputs, at most [limit]: interrupts, task stops, option changes and permission
     * answers first, then messages. A delivery left unacknowledged for a minute, or to another execution, is offered
     * again (a message, or an answer to the same execution) or rejected, at most three times.
     *
     * @return The inputs, now `delivered`.
     * @throws CloudSessionNotFoundException When the session is gone.
     */
    suspend fun deliver(orgId: Uuid, sessionId: Uuid, epoch: Long, limit: Int): List<CloudInput>

    /**
     * Records the runner's outcomes for inputs delivered to this execution; any other id is ignored. A consumed
     * message becomes its `message.user` event, anything else an `input` event; a consumed answer closes its request.
     *
     * @return The inputs it finished.
     * @throws CloudSessionNotFoundException When the session is gone.
     */
    suspend fun acknowledge(orgId: Uuid, sessionId: Uuid, epoch: Long, acks: List<RunnerInputAck>): List<CloudInput>

    /** @return Whether the session's conversation can be resumed, as the newest `status` event says. */
    suspend fun conversation(orgId: Uuid, sessionId: Uuid): ConversationResume
}
