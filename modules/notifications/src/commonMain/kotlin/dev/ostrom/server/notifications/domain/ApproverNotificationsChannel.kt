// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateChannel

/**
 * Tells each approver who may answer a gate that it waits for their yes: a push to every phone they registered in the
 * gate's organization, unless they turned requests off, and an e-mail to their proven address. A phone of another
 * organization is never read (each organization's phones are apart), and a phone whose credential was revoked or
 * expired is skipped. Only queued here ([NotificationOutbox]): the request never waits for a provider.
 *
 * @param approvers Finds the approvers among the members.
 * @param devices The phones.
 * @param credentials Whether a phone's credential still works.
 * @param notices The texts.
 * @param outbox Sends them.
 * @param mailing Whether the instance sends e-mails at all (`OSTROM_RESEND_API_KEY` or `OSTROM_MAIL_DIR`).
 */
class ApproverNotificationsChannel(
    private val approvers: Approvers,
    private val devices: PushDevicesRepository,
    private val credentials: LiveCredentials,
    private val notices: ApprovalNotices,
    private val outbox: NotificationOutbox,
    private val mailing: Boolean,
) : GateChannel {
    override val name: String = "notifications"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        for (request in answerable(gate, requests)) {
            val approver = approvers.reach(gate.orgId, request.approverId) ?: continue
            if (approver.pushes) {
                val phones = devices.ofMember(gate.orgId, approver.memberId).filter { reachable(approver, it) }
                val messages = phones.map { notices.push(gate, request, it) }
                if (messages.isNotEmpty()) outbox.offer(Notice.Push(gate.id, messages))
            }
            val email = approver.email
            if (mailing && email != null) outbox.offer(Notice.Email(gate.id, notices.email(gate, request, email)))
        }
    }

    // Nothing to tell once a gate is decided: the app reads the request's state when it opens it.
    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = Unit

    private suspend fun reachable(approver: Approver, device: PushDevice): Boolean {
        val credential = device.credential ?: return false
        return credentials.live(approver.userId, credential)
    }
}

// The pending requests whose approver may say yes: never the asker while another approver can (the self-approval rule
// of the gates module), as the GitHub check names them.
private fun answerable(gate: Gate, requests: List<ApprovalRequest>): List<ApprovalRequest> {
    val pending = requests.filter { it.status == RequestStatus.PENDING }
    return pending.filterNot { request -> gate.askedBy.any { it.equals(request.approverId, ignoreCase = true) } }
        .ifEmpty { pending }
}
