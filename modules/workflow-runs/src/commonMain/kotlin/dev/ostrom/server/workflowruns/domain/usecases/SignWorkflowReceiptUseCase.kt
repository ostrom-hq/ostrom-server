// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.ReceiptDecision
import dev.ostrom.core.api.models.ReceiptNode
import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.receipts.domain.models.Verification
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the launcher asks at the end of a session (contract `signWorkflowReceipt`).
 *
 * @property sessionId The session.
 * @property digest The package the session ran.
 * @property agent The agent, or null for the session's.
 * @property model The model, or null.
 */
data class WorkflowReceiptRequest(val sessionId: String, val digest: String, val agent: String?, val model: String?)

/**
 * A signed receipt with its verification.
 *
 * @property receipt The receipt, as signed.
 * @property verification Its check against the instance's keys.
 */
data class VerifiedReceipt(val receipt: JsonObject, val verification: Verification)

/**
 * Signs the workflow receipt of a session (prototype `POST /api/compiler/sessions/:id/receipt`): what each node of
 * its workflows did, the yes and no of its gates, and the package it ran. Signed once: asked again, the same receipt.
 */
interface SignWorkflowReceiptUseCase {
    /**
     * Signs the receipt.
     *
     * @param actor The caller: the agent token of the session's launch, or the session's person.
     * @param request The session and its package.
     *
     * @return The receipt and its verification.
     *
     * @throws dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException When the session is unknown.
     * @throws dev.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException When the package is unknown.
     * @throws dev.ostrom.server.workflowruns.domain.models.SessionStillOpenException When the session has not ended.
     */
    suspend operator fun invoke(actor: GateActor, request: WorkflowReceiptRequest): VerifiedReceipt
}

/**
 * [SignWorkflowReceiptUseCase] on the receipts module.
 *
 * @param access Who may write for a session.
 * @param sessions The sessions' gates.
 * @param repository The runs and notifications.
 * @param sign Signs receipts.
 * @param verify Verifies them.
 * @param ci The forge's record of a CI run, the proof of a `ci` check.
 */
class SignWorkflowReceiptUseCaseImpl(
    private val access: RunAccess,
    private val sessions: RunSessions,
    private val repository: WorkflowRunsRepository,
    private val sign: SignReceiptUseCase,
    private val verify: VerifyReceiptUseCase,
    private val ci: CiChecks,
) : SignWorkflowReceiptUseCase {
    override suspend fun invoke(actor: GateActor, request: WorkflowReceiptRequest): VerifiedReceipt {
        RunTexts.bounded("agent", request.agent)
        RunTexts.bounded("model", request.model)
        // Once the session has ended: the receipt is signed once, with everything the session reported.
        val session = access.ended(actor, request.sessionId)
        val pack = access.pack(actor, request.digest, session)
        val orgId = actor.orgId
        val all = RunQuery(sessionId = session.id, limit = MAX_ITEMS)
        val gates = sessions.gates(orgId, session.id)
        val runs = repository.checks(orgId, all).reversed()
        val notifications = repository.notifications(orgId, all).reversed()
        val proofs = NodeProofs(repository.attestations(orgId, session.id), ciChecks(session, pack, runs))
        val nodes = NodeOutcomes(pack, session, runs, notifications, gates, proofs).nodes()
        val decisions = gates.flatMap { it.decisions }.map {
            ReceiptDecision(
                approvalId = it.approvalId.toString(),
                by = it.by,
                at = it.at.isoMillis(),
                result = if (it.approved) "approved" else "rejected",
                selfApproved = it.selfApproved.takeIf { self -> self },
            )
        }
        val nodesJson = OstromJson.encodeToJsonElement(ListSerializer(ReceiptNode.serializer()), nodes)
        val digest = Digests.digestOf(
            buildJsonObject {
                put("session", session.id)
                put("package", pack.digest)
                put("nodes", nodesJson)
            },
        )
        val draft = buildJsonObject {
            put(
                "subject",
                buildJsonObject {
                    put("kind", "workflow")
                    put("id", "${session.id}@${pack.version}")
                    put("digest", digest)
                },
            )
            put("initiator", session.dev)
            session.launchId?.let { put("launchId", it) }
            put("sessionId", session.id)
            put("agent", request.agent ?: session.agent)
            request.model?.let { put("model", it) }
            put("rules", JsonArray(emptyList()))
            put("decisions", OstromJson.encodeToJsonElement(ListSerializer(ReceiptDecision.serializer()), decisions))
            put(
                "package",
                buildJsonObject {
                    put("version", pack.version)
                    put("digest", pack.digest)
                },
            )
            put("nodes", nodesJson)
        }
        val receipt = sign(orgId, draft, "rcpt_workflow_${session.id}")
        return VerifiedReceipt(receipt, verify.verify(orgId, receipt))
    }

    // The CI's run of each check node that ran, on the commit its last run reported, in the repository the session
    // really runs in (decision of 30/10 15:14). A check node's label is the name of its CI check. The forge's port
    // refuses a malformed repository or commit and answers nothing when GitHub fails: the node stays declared.
    private suspend fun ciChecks(session: Session, pack: RunPackage, runs: List<CheckRun>): Map<String, CiCheck> {
        val repo = session.realRepo ?: session.repo ?: return emptyMap()
        return pack.nodes.filter { it.kind == CHECK }.mapNotNull { node ->
            runs.lastOrNull { it.nodeId == node.id }?.head
                ?.let { head -> ci.latest(session.orgId, repo, head, node.label) }
                ?.let { node.id to it }
        }.toMap()
    }

    private companion object {
        const val CHECK = "check"

        /** The most runs and notifications a receipt reads: far above what one session reports. */
        const val MAX_ITEMS = 1_000
    }
}
