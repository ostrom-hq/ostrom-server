// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.infrastructure

import run.ostrom.core.receipts.Digests
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.services.DecisionSigner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The decisions' receipts, signed by the receipts module as the prototype's `decisions.ts` signs them: a rule change
 * (`recordRuleChange`: subject kind `rule-change`, its digest over `{ruleId, action, before, after}`, the rule and its
 * digest in `rules`) and a policy change (`recordSigned`: subject kind `policy`, its digest over
 * `{subject, action, before, after}`). Each names the decision as its one answer.
 *
 * @param signReceipt Signs and stores.
 */
class ReceiptsDecisionSigner(private val signReceipt: SignReceiptUseCase) : DecisionSigner {
    override fun digestOf(document: JsonObject): String = Digests.digestOf(document)

    override suspend fun sign(decision: Decision) {
        val digest = decision.ruleDigest
        val (kind, preimage, rules) = if (digest == null) {
            Triple(POLICY, changeOf(decision, "subject"), JsonArray(emptyList()))
        } else {
            val rule = buildJsonObject {
                put("id", decision.subject.id)
                put("digest", digest)
            }
            Triple(RULE_CHANGE, changeOf(decision, "ruleId"), JsonArray(listOf(rule)))
        }
        val draft = buildJsonObject {
            put(
                "subject",
                buildJsonObject {
                    put("kind", kind)
                    put("id", decision.subject.id)
                    put("digest", Digests.digestOf(preimage))
                },
            )
            put("initiator", decision.by)
            put("rules", rules)
            put(
                "decisions",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("approvalId", decision.id)
                            put("by", decision.by)
                            put("at", decision.at.isoMillis())
                            put("result", decision.action)
                        },
                    ),
                ),
            )
        }
        signReceipt(decision.orgId, draft, decision.receiptId)
    }

    private fun changeOf(decision: Decision, subjectField: String): JsonObject = buildJsonObject {
        put(subjectField, decision.subject.id)
        put("action", decision.action)
        put("before", decision.before ?: JsonNull)
        put("after", decision.after ?: JsonNull)
    }

    private companion object {
        const val RULE_CHANGE = "rule-change"
        const val POLICY = "policy"
    }
}
