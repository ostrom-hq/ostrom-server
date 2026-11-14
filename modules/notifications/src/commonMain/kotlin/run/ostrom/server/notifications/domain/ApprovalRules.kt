// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.OrgEventType
import kotlin.uuid.Uuid

/**
 * One approver's request, as `cloud.approval.requested` names it.
 *
 * @property eventSeq The event.
 * @property approvalId The request.
 * @property gateId Its gate.
 * @property approver The approver's login.
 */
data class ApprovalEvent(val eventSeq: Long, val approvalId: Uuid, val gateId: Uuid, val approver: String)

/**
 * Who is told of a request for a yes, and how: the router's approval rules, apart from [NotificationRules] (whose
 * kinds are the members' switches). Pure: every read happened before.
 */
object ApprovalRules {
    /** The journal types that tell an approver. */
    val SOURCE_TYPES: Set<OrgEventType> = setOf(OrgEventType.APPROVAL_REQUESTED)

    /** The kind of an approval's rows in the deliveries queue: never a [NotificationKind], never a member switch. */
    const val KIND: String = "approval"

    /** The way an approval's e-mail goes. */
    const val EMAIL_CHANNEL: String = "email"

    /**
     * @param entry A journal event.
     *
     * @return The request it names, or null for another type or data the gates never write.
     */
    fun event(entry: JournalEntry): ApprovalEvent? {
        if (entry.type != OrgEventType.APPROVAL_REQUESTED) return null
        val approvalId = entry.text("approvalId")?.let(Uuid::parseOrNull)
        val gateId = entry.text("gateId")?.let(Uuid::parseOrNull)
        val approver = entry.text("approver")
        return if (approvalId != null && gateId != null && approver != null) {
            ApprovalEvent(entry.seq, approvalId, gateId, approver)
        } else {
            null
        }
    }

    /**
     * Whether [login] is still asked to answer [gate]: the gate is open, their request waits, and they did not ask for
     * the action while another approver may answer it (the gates' self-approval rule, as the GitHub check names them).
     *
     * @param gate The gate as it is now.
     * @param login The approver's login, any case.
     *
     * @return Whether to tell them.
     */
    fun asks(gate: GateSummary, login: String): Boolean {
        if (!gate.open) return false
        val pending = gate.pendingApprovers
        val answerable = pending.filterNot { approver -> gate.askedBy.any { it.equals(approver, ignoreCase = true) } }
            .ifEmpty { pending }
        return answerable.any { it.equals(login, ignoreCase = true) }
    }

    /**
     * @param approver The approver.
     * @param mails Whether the instance sends e-mails now.
     *
     * @return The ways they are told: a push unless they turned requests off, an e-mail to their proven address.
     */
    fun channels(approver: Approver, mails: Boolean): List<String> = listOfNotNull(
        PUSH_CHANNEL.takeIf { approver.pushes },
        EMAIL_CHANNEL.takeIf { mails && approver.email != null },
    )

    private fun JournalEntry.text(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
