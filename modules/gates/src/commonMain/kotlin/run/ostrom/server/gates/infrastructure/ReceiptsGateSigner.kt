// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.infrastructure

import run.ostrom.core.receipts.Digests
import run.ostrom.core.receipts.SubjectPreimages
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.InvalidGateRequestException
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.receipts.domain.services.ReceiptRules
import run.ostrom.server.receipts.domain.services.receiptRulesJson
import run.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.isoMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Signs a gate's decision with the receipts module, the receipt the prototype signs (`bridge/index.ts`,
 * `onApprovalDecided`): its subject digest is the hash of what the approver was shown (gate, class, command,
 * repository, session, CI context, shown digest, pull request or push), and it cites the rules in force and the
 * answer. The receipt id comes from the gate, so signing twice returns the same receipt.

 *
 * @param signReceipt Signs and stores.
 * @param getReceipt Reads a stored receipt.
 * @param repository Reads the answered request.
 * @param sessions Names the agent of the session that asked.
 * @param rules The rules in force in the repository.
 */
class ReceiptsGateSigner(
    private val signReceipt: SignReceiptUseCase,
    private val repository: GatesRepository,
    private val sessions: GateSessions,
    private val rules: ReceiptRules,
    private val getReceipt: GetReceiptUseCase,
) : GateReceiptSigner {
    override suspend fun sign(gate: Gate): String {
        val decided = repository.requestsOf(gate.orgId, gate.id)
            .firstOrNull { it.status == RequestStatus.APPROVED || it.status == RequestStatus.REJECTED }
        val at = gate.decidedAt
        if (decided == null || at == null) throw InvalidGateRequestException("This gate is not decided yet.")
        val decision = buildJsonObject {
            put("approvalId", decided.id.toString())
            put("by", gate.decidedBy ?: decided.approverId)
            put("at", at.isoMillis())
            put("result", if (decided.status == RequestStatus.APPROVED) "approved" else "rejected")
            if (gate.selfApproved) put("selfApproved", true)
        }
        val draft = buildJsonObject {
            put(
                "subject",
                buildJsonObject {
                    put("kind", "gate")
                    put("id", subjectIdOf(gate))
                    put("digest", Digests.digestOf(preimage(gate)))
                },
            )
            put("initiator", gate.askedBy.firstOrNull() ?: "agent")
            // The launch and the session that asked first, as the subject's preimage names its first session.
            gate.launchIds.firstOrNull()?.let { put("launchId", it) }
            gate.sessionIds.firstOrNull()?.let { put("sessionId", it) }
            put("agent", agentOf(gate))
            put("rules", receiptRulesJson(rules.rulesFor(gate.orgId, gate.subject.repo)))
            put("decisions", JsonArray(listOf(decision)))
        }
        val receipt = signReceipt(gate.orgId, draft, "rcpt_gate_${gate.id}")
        return (receipt.getValue("id") as JsonPrimitive).content
    }

    override suspend fun read(orgId: Uuid, receiptId: String?): JsonObject? =
        receiptId?.let { getReceipt(orgId, it) }

    // A gate on a commit names it in clear, the pull request's head or the pushed commit (decided 02/11/2026): the
    // receipt says at a glance which commit was approved, and a SHA is no secret. The digest stays the request's hash.
    private fun subjectIdOf(gate: Gate): String {
        val pullRequest = gate.subject.pullRequest
        val pushed = gate.subject.push?.refs.orEmpty().firstOrNull()
        return when {
            pullRequest != null -> pullRequest.headSha
            pushed != null -> pushed.sha
            else -> gate.id.toString()
        }
    }

    private suspend fun agentOf(gate: Gate): String = when {
        AskedVia.CI in gate.askedVia -> "ci"
        else -> gate.sessionIds.firstOrNull()?.let { sessions.agentOf(gate.orgId, it) } ?: "agent"
    }

    /** The prototype's gate preimage, field for field. */
    internal fun preimage(gate: Gate): JsonObject {
        val subject = gate.subject
        val pr = subject.pullRequest
        val node = gate.node
        return SubjectPreimages.gate(
            gate = if (node == null) JsonNull else nodeJson(node),
            cls = subject.actionClass,
            command = subject.command,
            repo = subject.repo,
            sessionId = gate.sessionIds.firstOrNull(),
            ci = pr?.takeIf { AskedVia.CI in gate.askedVia }?.let {
                buildJsonObject {
                    put("sha", it.headSha)
                    put("pr", it.number.toString())
                }
            },
            shown = gate.shownDigest,
            pr = pr?.let {
                buildJsonObject {
                    put("number", it.number.toString())
                    put("sha", it.headSha)
                    put("owner", it.base)
                }
            },
            askedVia = gate.askedVia.map { it.name.lowercase() }.sorted(),
            push = subject.push?.let { push -> SubjectPreimages.Push(push.remote, push.refs.map { it.ref to it.sha }) },
        )
    }

    private fun nodeJson(node: GateNode): JsonElement = buildJsonObject {
        put("workflowId", node.workflowId)
        put("workflowName", node.workflowName)
        put("nodeId", node.nodeId)
        put("label", node.name)
        put("approvers", JsonArray(node.approvers.map(::JsonPrimitive)))
        put("status", node.workflowStatus)
        put("enforce", node.enforced)
        put("basis", node.basis)
        put("reason", node.reason)
    }
}
