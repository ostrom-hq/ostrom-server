// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.repositories.RulesTransaction
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The decisions on rules (BOUCLE, prototype `decisions.ts`): what a person did to a rule, read from before and after,
 * signed, and stored in the same transaction as the change.
 */
object RuleDecisions {
    /** What a deletion is called in the decisions. */
    const val DELETED: String = "deleted"

    /** The subject kind of a rule decision. */
    const val RULE: String = "rule"

    private const val LABEL = 160

    // A decided rule is to be reviewed 90 days after the decision, until its owner sets another date (BOUCLE 27/10).
    private val reviewAfter = 90.days

    /**
     * What a person did, or null when nothing a decision covers changed (a review date alone, the same status again).
     *
     * @param before The rule before, or null for a new one.
     * @param after The rule after, or null once deleted.
     * @return The rule's new status (`accepted`, `edited`, `rejected`, `proposed`), [DELETED], or null.
     */
    fun action(before: Rule?, after: Rule?): String? = when {
        after == null -> DELETED
        before == null || before.status != after.status -> after.status.value
        RuleDocuments.picked(before) != RuleDocuments.picked(after) -> RuleStatus.EDITED.value
        else -> null
    }

    /**
     * The review date of a rule decided at [at]: 90 days later.
     *
     * @param at The decision.
     * @return The date, `YYYY-MM-DD`.
     */
    fun reviewByOf(at: Instant): String = (at + reviewAfter).toString().take(DATE)

    /**
     * Records the decision of a change, when there is one; its receipt is signed once the transaction commits.
     *
     * @param signer Gives the rule's digest.
     * @param decider Who decided.
     * @param at When.
     * @param before The rule before, or null for a new one.
     * @param after The rule after, or null once deleted.
     * @return The decision, not signed yet, or null when nothing a decision covers changed.
     */
    suspend fun RulesTransaction.record(
        signer: DecisionSigner,
        decider: Decider,
        at: Instant,
        before: Rule?,
        after: Rule?,
    ): Decision? {
        // The decision names the rule as it is after, or as it was for a deletion.
        val rule = after ?: before
        val action = action(before, after)
        if (rule == null || action == null) return null
        val decision = Decision(
            orgId = decider.orgId,
            id = "dec_${Uuid.random()}",
            at = at,
            by = decider.name,
            subject = DecisionSubject(RULE, rule.id, rule.statement.take(LABEL)),
            action = action,
            before = before?.let(RuleDocuments::picked),
            after = after?.let(RuleDocuments::picked),
            ruleDigest = signer.digestOf(RuleDocuments.full(rule)),
        )
        insertDecision(decision)
        return decision
    }

    private const val DATE = 10
}
