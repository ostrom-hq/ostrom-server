// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.kernel.observability.OstromMetrics

/**
 * Counts the gates that ask for a yes and the answers they get ([OstromMetrics]): a channel like the others, told by
 * the notifier after the transaction, so a gate is counted once it is committed and a failure here never touches it.
 *
 * @param metrics The server's instruments.
 */
class MetricsGateChannel(private val metrics: OstromMetrics) : GateChannel {
    override val name: String = "metrics"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        metrics.gateRequested(kindOf(gate.subject.actionClass))
    }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) {
        val decidedAt = gate.decidedAt ?: return
        val answered = requests.firstOrNull { it.status in ANSWERED } ?: return
        val answer = if (gate.status == GateStatus.APPROVED) "yes" else "no"
        // An if, not `?.let … ?:`: the elvis would test a result that is never null, a branch no test reaches.
        val by = answered.channel
        val channel = if (by == null) UNKNOWN else by.name.lowercase()
        metrics.gateAnswered(answer, channel, decidedAt - gate.createdAt)
    }

    /** The bounded labels of the gate metrics. */
    companion object {
        /**
         * The metric label of an action class: the known classes, `custom` for every `custom-<slug>`, `other` for the
         * rest, so a label never carries a name an organization chose.
         *
         * @param actionClass The gate's action class.
         * @return Its label.
         */
        fun kindOf(actionClass: String): String = when {
            actionClass in KINDS -> actionClass.replace('-', '_')
            actionClass.startsWith(CUSTOM_PREFIX) -> "custom"
            else -> "other"
        }
    }
}

// Top-level, not in the companion: a private companion property has a getter nothing calls.
private val ANSWERED = setOf(RequestStatus.APPROVED, RequestStatus.REJECTED)
private val KINDS = setOf("push-main", "push-tag", "merge", "deploy", "migration")
private const val CUSTOM_PREFIX = "custom-"
private const val UNKNOWN = "unknown"
