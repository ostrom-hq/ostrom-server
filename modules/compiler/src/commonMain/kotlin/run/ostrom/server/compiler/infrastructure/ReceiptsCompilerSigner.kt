// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.infrastructure

import run.ostrom.core.receipts.Digests
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.services.HarnessReceiptSigner
import run.ostrom.server.compiler.domain.services.HarnessRule
import run.ostrom.server.compiler.domain.usecases.WorkflowDecisionSigner
import run.ostrom.server.receipts.domain.services.ReceiptRule
import run.ostrom.server.receipts.domain.services.receiptRulesJson
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The compiler's receipts, signed by the receipts module as the prototype signs them: a harness version
 * (`bridge/harness.ts`, decided rules only, no decision) and a decision on a workflow (`decisions.ts`
 * `recordSigned`, receipt kind `workflow`).
 *
 * @param signReceipt Signs and stores.
 */
class ReceiptsCompilerSigner(
    private val signReceipt: SignReceiptUseCase,
) : HarnessReceiptSigner, WorkflowDecisionSigner {
    override suspend fun sign(
        orgId: Uuid,
        version: String,
        digest: String,
        rules: List<HarnessRule>,
        at: Instant,
    ): String {
        val draft = buildJsonObject {
            put("subject", subject("harness", version, digest))
            put("initiator", "ostrom compiler")
            put("agent", "ostrom export")
            val decided = rules.filter { it.rule.decided }
            put("rules", receiptRulesJson(decided.map { ReceiptRule(it.rule.id, it.digest) }))
            put("decisions", JsonArray(emptyList()))
        }
        return idOf(signReceipt(orgId, draft, "rcpt_harness_$version"))
    }

    override suspend fun sign(
        orgId: Uuid,
        decision: DecisionDoc,
        action: String,
        before: JsonObject?,
        after: JsonObject,
    ): String {
        val preimage = buildJsonObject {
            put("subject", decision.subjectId)
            put("action", action)
            put("before", before ?: JsonNull)
            put("after", after)
        }
        val answer = buildJsonObject {
            put("approvalId", decision.id)
            put("by", decision.by)
            put("at", decision.at)
            put("result", action)
        }
        val draft = buildJsonObject {
            put("subject", subject("workflow", decision.subjectId, Digests.digestOf(preimage)))
            put("initiator", decision.by)
            put("rules", JsonArray(emptyList()))
            put("decisions", JsonArray(listOf(answer)))
        }
        return idOf(signReceipt(orgId, draft, null))
    }

    private fun subject(kind: String, id: String, digest: String) = buildJsonObject {
        put("kind", kind)
        put("id", id)
        put("digest", digest)
    }

    private fun idOf(receipt: JsonObject): String = (receipt.getValue("id") as JsonPrimitive).content
}
