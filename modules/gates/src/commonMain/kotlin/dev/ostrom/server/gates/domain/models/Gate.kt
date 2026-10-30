// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.models

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One request for a human yes before a boundary action (a push to main, a merge, a deploy…). A gate holds one
 * [ApprovalRequest] per approver; the first yes closes the others in the same transaction, so a gate opens at most
 * once. The yes is bound to [subject] through [key] and [shownDigest]: another commit, another diff, another base,
 * another repository or a force-push is another gate.
 *
 * @property id The gate id.
 * @property orgId The organization that owns it; every read is scoped by it.
 * @property key The digest of what the yes covers ([GateKeys]): a second path (the agent's hook, the GitHub check)
 * asking for the same action joins this gate instead of asking a second yes.
 * @property subject What the yes covers.
 * @property node The workflow gate node that asked, or null when no workflow gates the action.
 * @property label The action, as shown to the approvers.
 * @property mode Whether the gate blocks, only records, or does not apply.
 * @property status Where the gate is.
 * @property askedBy Who asked for the action (the developer of each session, the author of the pull request). None
 * of them may say yes while another approver exists.
 * @property askedVia The paths that asked (hook, GitHub check, MCP).
 * @property sessionIds The agent sessions that asked or joined (the session receipts list the gate).
 * @property shownDigest The digest of what the approvers are shown ([evidence] and [subject]); a yes names it.
 * @property evidence What the approvers are shown, computed by the server.
 * @property decidedBy The approver who answered, or null while pending.
 * @property decidedAt When the gate was answered.
 * @property selfApproved True when the yes came from someone who asked, as the only possible approver.
 * @property receiptId The signed receipt of the decision, once signed.
 * @property launchIds The launches whose agent tokens asked for it (an agent token reads and uses only its launch's
 *   gates).
 * @property reservedBy The launch that reserved the yes before running the action.
 * @property reservedAt When it was reserved.
 * @property consumedAt When the action ran and the yes was used up.
 * @property createdAt When the gate was opened.
 */
data class Gate(
    val id: Uuid,
    val orgId: Uuid,
    val key: String,
    val subject: GateSubject,
    val node: GateNode?,
    val label: String,
    val mode: GateMode,
    val status: GateStatus,
    val askedBy: List<String>,
    val askedVia: Set<AskedVia>,
    val sessionIds: List<String>,
    val launchIds: List<String> = emptyList(),
    val shownDigest: String,
    val evidence: List<Evidence>,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val selfApproved: Boolean = false,
    val receiptId: String? = null,
    val reservedBy: String? = null,
    val reservedAt: Instant? = null,
    val consumedAt: Instant? = null,
    val createdAt: Instant,
) {
    /**
     * Whether an approved yes outlived [YES_LIFETIME] without being reserved (SECURITY-AUDIT S2-29): it no longer
     * covers the action, which asks a new yes. A reserved yes is not expired: its action is already running.
     *
     * @param now The current time.
     * @return True for an approved yes given [YES_LIFETIME] or more ago.
     */
    fun yesExpired(now: Instant): Boolean =
        status == GateStatus.APPROVED && decidedAt?.let { now - it >= YES_LIFETIME } == true

    /**
     * Whether a reservation outlived [RESERVATION_LIFETIME] without its action reporting back (the agent died before
     * consuming or releasing): the same action is then asked anew instead of waiting on it forever.
     *
     * @param now The current time.
     * @return True for a gate reserved [RESERVATION_LIFETIME] or more ago.
     */
    fun reservationLapsed(now: Instant): Boolean =
        status == GateStatus.RESERVED && reservedAt?.let { now - it >= RESERVATION_LIFETIME } == true

    /** The gate's constants. */
    companion object {
        /** How long a reserved yes waits for its action to consume or release it. */
        val RESERVATION_LIFETIME: Duration = 30.minutes

        /** How long a yes waits for its action: one yes is one head commit and one destination, used soon. */
        val YES_LIFETIME: Duration = 15.minutes
    }
}

/**
 * The workflow node that gates an action, as compiled for the organization.
 *
 * @property workflowId The workflow.
 * @property nodeId The gate node in it.
 * @property name The gate's name, shown to the approvers.
 * @property approvers The users who may say yes, in the declared order.
 * @property enforced False while the workflow is only proposed: the gate records without blocking (shadow).
 * @property workflowName The workflow's name.
 * @property workflowStatus The workflow's status (`proposed`, `accepted`, `edited`).
 * @property basis `declared` when the gate names the action, `guessed` when the catalog matched it.
 * @property reason Where the approvers come from, shown with the gate.
 */
data class GateNode(
    val workflowId: String,
    val nodeId: String,
    val name: String,
    val approvers: List<String>,
    val enforced: Boolean,
    val workflowName: String = workflowId,
    val workflowStatus: String = if (enforced) "accepted" else "proposed",
    val basis: String = "declared",
    val reason: String = "",
)

/**
 * One line the approvers are shown.
 *
 * @property label What the line is (`Repository`, `Commits`, `Diff`…).
 * @property value Its value.
 */
data class Evidence(val label: String, val value: String) {
    /** The labels other modules read. */
    companion object {
        /** The diff the developer's machine sent with a push: Ostrom shows it, it does not check it. */
        const val MACHINE_DIFF: String = "Diff (sent by the developer's machine, not checked by Ostrom)"
    }
}

/** Whether a gate blocks the action. */
enum class GateMode {
    /** A decided workflow gates the action: nothing runs without a yes. */
    ENFORCED,

    /** The workflow is only proposed: the request is recorded, the action is not blocked. */
    SHADOW,

    /** No workflow gates this action: recorded for the session receipt only. */
    UNGATED,
}

/** Where a gate is. */
enum class GateStatus {
    /** Waiting for a yes or a no. */
    PENDING,

    /** An approver said yes; the action may run once. */
    APPROVED,

    /** A session reserved the yes before running the action (PreToolUse); nobody else can use it. */
    RESERVED,

    /** The action ran (PostToolUse): the yes is used up. */
    CONSUMED,

    /** An approver said no. */
    REJECTED,

    /** Recorded without blocking ([GateMode.SHADOW]). */
    SHADOW,

    /** Recorded, no gate applies ([GateMode.UNGATED]). */
    UNGATED,
}

/** The path that asked for a gate. */
enum class AskedVia { HOOK, CI, MCP }

/** Whether the person who asked may approve their own action. */
enum class SelfApprovalPolicy {
    /** Only when they are the only possible approver; the yes is then marked self-approved in the receipt. */
    SOLE_APPROVER,

    /** Never: the request waits until someone else is added to the gate. */
    NEVER,
}
