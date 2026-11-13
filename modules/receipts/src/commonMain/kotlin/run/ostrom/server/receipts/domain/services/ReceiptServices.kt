// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.domain.services

import run.ostrom.server.receipts.domain.models.ReceiptKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** Signs a receipt body with the instance key, in format v1. */
interface ReceiptBodySigner {
    /**
     * Signs every field of [body], known or not.
     *
     * @param body The receipt without its `signature` field.
     *
     * @return The body with its `signature` field added.
     */
    suspend fun sign(body: JsonObject): JsonObject
}

/** The public keys a receipt of this instance is verified against. */
interface TrustedReceiptKeys {
    /**
     * Lists the keys.
     *
     * @return Every key the instance signs or once signed with, oldest first.
     */
    suspend fun keys(): List<ReceiptKey>
}

/**
 * A decided rule a receipt cites: the rules in force where the action ran (`rules` of a gate or session receipt).
 *
 * @property id The rule.
 * @property digest The digest of the rule as decided.
 */
data class ReceiptRule(val id: String, val digest: String)

/** The decided rules of a repository, cited by the gate and session receipts (the rules module binds the real one). */
interface ReceiptRules {
    /**
     * Lists the rules in force in a repository.
     *
     * @param orgId The organization.
     * @param repo The repository, or null outside one.
     * @return The rules, in the order the receipt cites them.
     */
    suspend fun rulesFor(orgId: Uuid, repo: String?): List<ReceiptRule>
}

/**
 * The `rules` field of a receipt.
 *
 * @param rules The rules in force.
 * @return `[{id, digest}]`.
 */
fun receiptRulesJson(rules: List<ReceiptRule>): JsonArray = JsonArray(
    rules.map {
        buildJsonObject {
            put("id", it.id)
            put("digest", it.digest)
        }
    },
)
