// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

// A conversation session's transcript, inputs and subagents (TeamCode's CloudConversationModels.kt,
// docs/cloud-conversations.md), with Ostrom's organizations and ISO-8601 instants.

/**
 * How a session's agent is talked to, fixed at creation.
 *
 * @property wire The stable name in the API and the database.
 */
enum class SessionMode(val wire: String) {
    /** A PTY relayed over the terminal WebSocket: every session before conversations, and the default. */
    TERMINAL("terminal"),

    /** The agent runs headless; members read its transcript and send it messages (`runners.cloud.conversation.v1`). */
    CONVERSATION("conversation"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The agents that run headless: Claude Code (stream-json) and Codex (`app-server`). */
        val CONVERSATION_AGENTS: Set<AgentKind> = setOf(AgentKind.CLAUDE, AgentKind.CODEX)

        /**
         * @param wire A stored or requested name.
         * @return The mode, or null when the name is unknown.
         */
        fun fromWire(wire: String): SessionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The owner's standing approval of the session's tool uses (TeamCode's Bypass, `runners.cloud.permissions.bypass.v1`).
 *
 * @property by The owner who turned it on.
 * @property credentialId The credential they used, which the server's own answers are written with.
 * @property at When.
 */
data class PermissionBypass(val by: String, val credentialId: String?, val at: Instant)

/**
 * Why the session's agent waits for a person (TeamCode's `CloudHumanWait`): a `permission.request` still open.
 *
 * @property reason `permission` (a tool use to approve) or `question`.
 * @property requestId What the permissions route takes.
 * @property since When the request was stored.
 * @property eventSeq The `permission.request` event.
 * @property epoch The execution that asked; another one cannot answer it.
 */
data class CloudHumanWait(
    val reason: String,
    val requestId: String,
    val since: Instant,
    val eventSeq: Long,
    val epoch: Long,
)

/**
 * Normalized transcript event types; nothing else is stored. Thinking blocks have no type and never reach the server.
 *
 * @property wire The stable name in the API and the database.
 */
enum class CloudEventType(val wire: String) {
    USER_MESSAGE("message.user"),
    AGENT_MESSAGE("message.agent"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),

    /** Live text of an agent message while it is written, replaced by its `message.agent`. */
    AGENT_MESSAGE_DELTA("message.agent.delta"),
    TURN("turn"),
    STATUS("status"),
    ERROR("error"),

    /** The agent waits for a person before using a tool; answered by a [CloudInputKind.PERMISSION] input. */
    PERMISSION_REQUEST("permission.request"),

    /** The runner denied a request nobody answered within the deadline: `{requestId, deadlineMinutes, message}`. */
    PERMISSION_EXPIRED("permission.expired"),

    /** The agent's live background tasks, the full set each time it changes. */
    TASKS("tasks"),

    /** Written by the server when an input is consumed, cancelled or rejected. */
    INPUT("input"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param value A stored name.
         * @return The type, or null when the name is unknown.
         */
        fun parse(value: String): CloudEventType? = entries.singleOrNull { it.wire == value }
    }
}

/**
 * One transcript event.
 *
 * @property seq From 1, increased by one per session.
 * @property at When the server stored it.
 * @property epoch The execution that produced it; 0 for an event the server wrote.
 * @property data A bounded, secret-masked JSON object (the kernel's `TranscriptSanitizer`), encoded.
 * @property feed The `tool.call` that started the subagent the event belongs to; null for the main agent.
 */
data class CloudEvent(
    val sessionId: Uuid,
    val seq: Long,
    val type: CloudEventType,
    val at: Instant,
    val epoch: Long,
    val data: String,
    val feed: String? = null,
)

/**
 * A subagent of a session: [id] is the `tool.call` that started it and the feed of its events, [parentId] the feed it
 * was started from (null: the main agent's), [taskId] the live background task standing for it, if one was reported.
 */
data class CloudSubagent(
    val id: String,
    val type: String?,
    val description: String,
    val state: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val steps: Int,
    val parentId: String?,
    val taskId: String?,
)

/**
 * A page of events in ascending order.
 *
 * @property firstSeq The oldest retained event of the page's feed.
 * @property lastSeq The session's newest event.
 * @property nextAfter Continue forward from here; null when nothing newer.
 * @property nextBefore Continue backward from here; null when nothing older.
 */
data class CloudEventPage(
    val events: List<CloudEvent>,
    val firstSeq: Long?,
    val lastSeq: Long,
    val nextAfter: Long?,
    val nextBefore: Long?,
)

/**
 * What a member may send to an agent.
 *
 * @property wire The stable name in the API and the database.
 */
enum class CloudInputKind(val wire: String) {
    MESSAGE("message"),
    INTERRUPT("interrupt"),

    /** The answer to a `permission.request`; its text is the encoded answer, read by the runner. */
    PERMISSION("permission"),

    /** Stops one background task of the running agent; its text is the task id. */
    STOP_TASK("stop_task"),

    /** Changes the model of the running agent from its next turn on; its text is the model. */
    SET_MODEL("set_model"),

    /** Changes the effort of the running agent from its next turn on; its text is the effort. */
    SET_EFFORT("set_effort"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param value A stored name.
         * @return The kind; [MESSAGE] for a name this version does not know.
         */
        fun parse(value: String): CloudInputKind = entries.singleOrNull { it.wire == value } ?: MESSAGE
    }
}

/**
 * An input's acknowledgement level.
 *
 * @property wire The stable name in the API and the database.
 */
enum class CloudInputStatus(val wire: String) {
    QUEUED("queued"),
    DELIVERED("delivered"),
    CONSUMED("consumed"),
    CANCELLED("cancelled"),
    REJECTED("rejected"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The statuses an input is still pending in. */
        val PENDING: List<CloudInputStatus> = listOf(QUEUED, DELIVERED)

        /**
         * @param value A stored name.
         * @return The status; [QUEUED] for a name this version does not know.
         */
        fun parse(value: String): CloudInputStatus = entries.singleOrNull { it.wire == value } ?: QUEUED
    }
}

/**
 * A message, an interrupt, a permission answer, a task stop or a model or effort change, and its acknowledgement.
 *
 * @property authorId The member who sent it: always the caller, never someone they act for.
 * @property credentialId The credential they sent it with.
 * @property woke Accepting it resumed a paused session.
 * @property clientKey The idempotency key (header or `clientMessageId`), scoped to the session and author.
 * @property requestDigest What the key stands for, so the same key with another request is refused.
 * @property attempts Deliveries so far.
 * @property eventSeq The transcript event recording its end.
 */
data class CloudInput(
    val id: Uuid,
    val orgId: Uuid,
    val sessionId: Uuid,
    val kind: CloudInputKind,
    val status: CloudInputStatus,
    val authorId: String,
    val credentialId: String?,
    val text: String?,
    val wake: Boolean,
    val clientKey: String?,
    val requestDigest: String?,
    val createdAt: Instant,
    val woke: Boolean = false,
    val deliveredAt: Instant? = null,
    val deliveredEpoch: Long? = null,
    val attempts: Int = 0,
    val consumedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val reason: String? = null,
    val eventSeq: Long? = null,
)

/** The session's inputs, newest first; [nextBefore] is the cursor of the following page. */
data class CloudInputPage(val inputs: List<CloudInput>, val nextBefore: Uuid?)

/**
 * A message or an interrupt as a member sent it.
 *
 * @property clientKey The `Idempotency-Key` header or `clientMessageId`.
 * @property requestDigest What [clientKey] stands for.
 * @property expectedLastEventSeq Refuses the input when the transcript has newer events.
 */
data class InputRequest(
    val text: String?,
    val wake: Boolean,
    val clientKey: String?,
    val requestDigest: String?,
    val expectedLastEventSeq: Long?,
)

/**
 * What a new input needs from the store, all checked and written in one transaction locked on the session row.
 *
 * @property expectedLastEventSeq Refuse with `409 stale_view` when the session has events after this sequence.
 * @property permissionRequestId For a permission answer: refused while another answer to this request is pending.
 * @property permissionCheck For a permission answer: the rules it must pass, given the session and its request as they
 *   are under the lock.
 */
data class NewInput(
    val input: CloudInput,
    val expectedLastEventSeq: Long?,
    val permissionRequestId: String? = null,
    val permissionCheck: ((AnswerContext) -> Unit)? = null,
)

/** What a permission answer is checked against, read under the session row lock: [request] is null when none. */
data class AnswerContext(
    val request: PermissionRequestRecord?,
    val state: SessionState,
    val desiredState: DesiredState,
    val currentEpoch: Long,
)

/** What inserting an input did. */
sealed interface InputOutcome {
    /** The input as stored, or as first stored. */
    val input: CloudInput

    /** A new input. */
    data class Accepted(override val input: CloudInput) : InputOutcome

    /** The idempotency key was used before with the same request: the first input. */
    data class Replayed(override val input: CloudInput) : InputOutcome
}

/** A member's answer to the `permission.request` [requestId]: [answers] (a question's answers) need [allow]. */
data class PermissionAnswer(
    val requestId: String,
    val allow: Boolean,
    val message: String?,
    val answers: List<QuestionAnswer>,
) {
    /** A denial without a message or answers: it only stops a tool use and carries no instruction for the agent. */
    val isBareDenial: Boolean get() = !allow && message == null && answers.isEmpty()
}

/** The answer to the question at index [question]: the chosen [options] by index, or free [text]. */
data class QuestionAnswer(val question: Int, val options: List<Int> = emptyList(), val text: String? = null)

/**
 * A `permission.request` as the transcript holds it.
 *
 * @property question A question to the user (`AskUserQuestion`) rather than a tool use gated by an `ask` rule.
 * @property questions Each question's shape; null when the input was left out of the event.
 * @property state What the later transcript says about it.
 * @property epoch The execution that asked; another one cannot answer it.
 * @property inputLeftOut The request's input was too large for the transcript: it can only be denied.
 */
data class PermissionRequestRecord(
    val requestId: String,
    val question: Boolean,
    val questions: List<QuestionShape>?,
    val state: PermissionRequestState = PermissionRequestState.OPEN,
    val epoch: Long = 0,
    val inputLeftOut: Boolean = false,
)

/** Where a permission request is. */
enum class PermissionRequestState {
    /** Nothing closed it yet. */
    OPEN,

    /** The agent consumed an answer to it. */
    ANSWERED,

    /** The runner denied it at the deadline (`permission.expired`). */
    EXPIRED,

    /** Its turn ended (a later `turn` event that completed, interrupted or failed it). */
    ENDED,
}

/** [text]: the question's text, which Claude Code keys answers by, so two answered questions must not share it. */
data class QuestionShape(val options: Int, val multiSelect: Boolean, val text: String? = null)
