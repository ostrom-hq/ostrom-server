// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateChannel
import kotlin.uuid.Uuid

/**
 * Each pending approver hears of a request on every phone they registered. Nothing is sent once the gate is decided:
 * the app reads the request's state when it opens it.
 *
 * @param devices The phones.
 * @param sender Expo's push service.
 * @param notices The texts.
 */
class PushGateChannel(
    private val devices: PushDevicesRepository,
    private val sender: PushSender,
    private val notices: ApprovalNotices,
) : GateChannel {
    override val name: String = "push"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        val messages = requests.filter { it.status == RequestStatus.PENDING }.flatMap { request ->
            devices.devices(gate.orgId, request.approverId).map { notices.push(gate, request, it.pushToken) }
        }
        if (messages.isNotEmpty()) sender.send(messages)
    }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = Unit
}

/**
 * Each pending approver whose address is known hears of a request by email, with the link to answer it.
 *
 * @param emails The members' addresses.
 * @param sender Resend.
 * @param notices The texts.
 */
class EmailGateChannel(
    private val emails: PersonEmails,
    private val sender: EmailSender,
    private val notices: ApprovalNotices,
) : GateChannel {
    override val name: String = "email"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        if (!sender.configured) return
        for (request in requests.filter { it.status == RequestStatus.PENDING }) {
            val to = emails.emailOf(gate.orgId, request.approverId) ?: continue
            sender.send(notices.email(gate, request, to))
        }
    }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = Unit
}

/** No address is known until the identity module binds its own [PersonEmails]. */
object NoPersonEmails : PersonEmails {
    override suspend fun emailOf(orgId: Uuid, personId: String): String? = null
}
