// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.services.GateChannel

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
 * @param mailing Whether the instance sends e-mails now (a mail provider set in the console, or a mail directory).
 */
class ApproverNotificationsChannel(
    private val approvers: Approvers,
    private val devices: PushDevicesRepository,
    private val credentials: LiveCredentials,
    private val notices: ApprovalNotices,
    private val outbox: NotificationOutbox,
    private val mailing: MailAvailability,
) : GateChannel {
    override val name: String = "notifications"

    // Only queued here: the reads run in the outbox, never inside the gate's request nor its channel timeout.
    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        outbox.offer(Notice.Plan(gate.id) { plan(gate, requests) })
    }

    // Once decided, nothing more about the gate: a notice still queued or retried would ask for a yes already given.
    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = outbox.close(gate.id)

    private suspend fun plan(gate: Gate, requests: List<ApprovalRequest>): List<Notice> = buildList {
        // Read once per plan: the same answer for every approver of the gate.
        val mails = mailing.available()
        for (request in answerable(gate, requests)) {
            val approver = approvers.reach(gate.orgId, request.approverId) ?: continue
            if (approver.pushes) {
                devices.ofMember(gate.orgId, approver.memberId).filter { reachable(approver, it) }
                    .forEach { add(Notice.Push(gate.id, listOf(notices.push(request, it)))) }
            }
            val email = approver.email
            if (email != null && mails) add(Notice.Email(gate.id, notices.email(gate, request, email)))
        }
    }

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
