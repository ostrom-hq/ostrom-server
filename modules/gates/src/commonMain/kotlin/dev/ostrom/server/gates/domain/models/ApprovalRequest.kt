package dev.ostrom.server.gates.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The request a gate sends to one approver. Only that approver answers it: never the person who asked (while another
 * approver exists), never an admin in their place, never a device or agent token.
 *
 * @property id The request id, carried by the Slack buttons, the push notification and the yes page.
 * @property gateId The gate it belongs to.
 * @property orgId The organization.
 * @property approverId The user who may answer it.
 * @property status Where it is.
 * @property answeredAt When it was answered or closed.
 * @property channel Where the answer came from.
 * @property createdAt When it was sent.
 */
data class ApprovalRequest(
    val id: Uuid,
    val gateId: Uuid,
    val orgId: Uuid,
    val approverId: String,
    val status: RequestStatus,
    val answeredAt: Instant? = null,
    val channel: AnswerChannel? = null,
    val createdAt: Instant,
)

/** Where an approval request is. */
enum class RequestStatus {
    /** Waiting for its approver. */
    PENDING,

    /** Its approver said yes: the gate is open. */
    APPROVED,

    /** Its approver said no: the gate is refused. */
    REJECTED,

    /** Another approver answered first: nothing to do any more. */
    CLOSED,
}

/** Where an answer was given. */
enum class AnswerChannel { CONSOLE, MOBILE, SLACK, EMAIL }

/** An answer to a request. */
enum class Answer { YES, NO }

/**
 * Who calls a gate use case, resolved from the credential by the identity module (never from the request body).
 *
 * @property id The stable id of the user, device, agent session or service.
 * @property orgId The organization the credential belongs to.
 * @property kind What the caller is.
 * @property sessionId The agent session an agent token is scoped to.
 */
data class GateActor(
    val id: String,
    val orgId: Uuid,
    val kind: ActorKind,
    val sessionId: Uuid? = null,
)

/** The kinds of caller. Only a [USER] answers a request. */
enum class ActorKind { USER, DEVICE, AGENT_SESSION, SERVICE }

/**
 * A gate and its requests, as a caller sees it.
 *
 * @property gate The gate.
 * @property requests One per approver.
 * @property waitingFor Who can still say yes: the pending approvers who did not ask, or the asker alone when they are
 * the only approver and the organization allows it. Empty once the gate is decided.
 */
data class GateView(
    val gate: Gate,
    val requests: List<ApprovalRequest>,
    val waitingFor: List<String>,
)
