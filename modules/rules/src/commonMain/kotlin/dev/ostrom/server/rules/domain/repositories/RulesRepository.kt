// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.repositories

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleQuery
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rules, decisions and gate policy of each organization; every call is confined to one organization. */
interface RulesRepository {
    /**
     * Lists rules.
     *
     * @param orgId The organization.
     * @param query The filters.
     * @param limit At most this many.
     * @return The rules, by group then id.
     */
    suspend fun rules(orgId: Uuid, query: RuleQuery, limit: Int): List<Rule>

    /**
     * Reads a rule.
     *
     * @param orgId The organization.
     * @param id The rule id.
     * @return The rule, or null when the organization has none with this id.
     */
    suspend fun rule(orgId: Uuid, id: String): Rule?

    /**
     * Lists decisions, newest first.
     *
     * @param orgId The organization.
     * @param query The filters and the page.
     * @return The decisions.
     */
    suspend fun decisions(orgId: Uuid, query: DecisionQuery): List<Decision>

    /**
     * Marks decisions as signed.
     *
     * @param orgId The organization.
     * @param ids The decisions whose receipt is signed.
     */
    suspend fun markSigned(orgId: Uuid, ids: List<String>)

    /**
     * Reads the self-approval setting.
     *
     * @param orgId The organization.
     * @return The setting, or null when the organization never chose.
     */
    suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy?

    /**
     * Runs [block] in one transaction of [orgId]: what it writes commits together, or not at all.
     *
     * @param orgId The organization.
     * @param block The work.
     * @return What [block] returns.
     */
    suspend fun <T> transaction(orgId: Uuid, block: suspend RulesTransaction.() -> T): T
}

/** The writes of one transaction of one organization. */
interface RulesTransaction {
    /**
     * Reads a rule and locks it until the transaction ends, so two decisions on it never interleave.
     *
     * @param id The rule id.
     * @return The rule, or null.
     */
    suspend fun lockRule(id: String): Rule?

    /**
     * Inserts a rule.
     *
     * @param rule The rule.
     */
    suspend fun insertRule(rule: Rule)

    /**
     * Replaces a rule's fields.
     *
     * @param rule The rule as it is now.
     */
    suspend fun updateRule(rule: Rule)

    /**
     * Deletes a rule.
     *
     * @param id The rule id.
     */
    suspend fun deleteRule(id: String)

    /**
     * Records a decision.
     *
     * @param decision The decision, its receipt not signed yet.
     */
    suspend fun insertDecision(decision: Decision)

    /**
     * Reads the self-approval setting and locks it until the transaction ends.
     *
     * @return The setting, or null when the organization never chose.
     */
    suspend fun lockSelfApproval(): SelfApprovalPolicy?

    /**
     * Sets the self-approval setting.
     *
     * @param policy The setting.
     * @param at When.
     */
    suspend fun putSelfApproval(policy: SelfApprovalPolicy, at: Instant)
}
