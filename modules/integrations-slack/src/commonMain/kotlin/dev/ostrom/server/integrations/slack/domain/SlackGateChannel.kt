// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateChannel

/**
 * Slack as a channel of the gates: each pending request goes as a direct message to its approver when they are
 * reached on Slack ([SlackLink]); once the gate is decided, every message of the gate shows the decision and loses
 * its buttons. Without a bot token, or for an approver not on Slack, the request waits in the console and the mobile
 * app. A failing call is thrown to the gates notifier, which logs it and goes on with the other channels.
 *
 * @param repository Links and messages.
 * @param api The Slack Web API.
 * @param messages The messages.
 */
class SlackGateChannel(
    private val repository: SlackRepository,
    private val api: SlackApi,
    private val messages: SlackMessages,
) : GateChannel {
    override val name: String = "slack"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        if (!api.configured) return
        for (request in requests.filter { it.status == RequestStatus.PENDING }) {
            val link = repository.link(gate.orgId, request.approverId) ?: continue
            val channel = api.openDirectMessage(link.userId)
            val ts = api.post(channel, messages.request(gate, request))
            repository.saveMessage(SlackMessageRef(gate.orgId, request.id, gate.id, channel, ts))
        }
    }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) {
        if (!api.configured) return
        val decided = messages.decided(gate)
        for (message in repository.messages(gate.orgId, gate.id)) api.update(message.channel, message.ts, decided)
    }
}
