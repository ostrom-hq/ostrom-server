// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

import run.ostrom.server.rules.domain.models.Decision
import kotlinx.serialization.json.JsonObject

/**
 * Signs the receipt of a decision (receipts module), as the prototype's `decisions.ts` does: a rule change is a
 * `rule-change` receipt that names the rule and its digest, a policy change a `policy` receipt.
 */
interface DecisionSigner {
    /**
     * The digest a receipt gives a document (the receipts' canonical JSON digest).
     *
     * @param document The document.
     * @return Its digest.
     */
    fun digestOf(document: JsonObject): String

    /**
     * Signs the decision's receipt, under [Decision.receiptId]; signing it again returns the same receipt.
     *
     * @param decision The decision.
     */
    suspend fun sign(decision: Decision)
}
