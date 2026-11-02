// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.usecases

import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.GateChange
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.NewRule
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleNotFoundException
import dev.ostrom.server.rules.domain.models.RulePatch
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.repositories.RulesTransaction
import dev.ostrom.server.rules.domain.services.DecisionSigner
import dev.ostrom.server.rules.domain.services.RuleChecks
import dev.ostrom.server.rules.domain.services.RuleDecisions
import dev.ostrom.server.rules.domain.services.RuleDecisions.record
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Writes a rule for the team: a signed decision. */
interface CreateRuleUseCase {
    /**
     * Writes it.
     *
     * @param decider Who writes it.
     * @param rule The rule.
     * @return The stored rule.
     * @throws InvalidRuleException When a field is out of bounds, or the status is neither accepted nor proposed.
     */
    suspend operator fun invoke(decider: Decider, rule: NewRule): Rule
}

/**
 * [CreateRuleUseCase] on the repository.
 *
 * @param repository The rules.
 * @param signer Gives the rule's digest.
 * @param signDecisions Signs the decision once committed.
 * @param clock The time.
 */
class CreateRuleUseCaseImpl(
    private val repository: RulesRepository,
    private val signer: DecisionSigner,
    private val signDecisions: SignDecisionsUseCase,
    private val clock: Clock,
) : CreateRuleUseCase {
    override suspend fun invoke(decider: Decider, rule: NewRule): Rule {
        if (rule.status != RuleStatus.ACCEPTED && rule.status != RuleStatus.PROPOSED) {
            throw InvalidRuleException("A new rule is accepted or proposed.")
        }
        val now = clock.now()
        val gate = rule.gate?.let(RuleChecks::gate)
        val draft = Rule(
            orgId = decider.orgId,
            id = "rule_${Uuid.random()}",
            statement = RuleChecks.statement(rule.statement),
            output = RuleChecks.output(rule.output ?: if (gate == null) RULE else GATE),
            group = RuleChecks.group(rule.group),
            scope = RuleChecks.scope(rule.scope),
            sources = emptyList(),
            why = rule.why?.let(RuleChecks::why),
            strength = rule.strength,
            confidence = 1.0,
            status = rule.status,
            foundBy = decider.name,
            foundAt = now,
            reviewBy = rule.reviewBy?.let(RuleChecks::reviewBy),
            decidedBy = null,
            decidedAt = null,
            origin = WRITTEN,
            gate = gate,
            updatedAt = now,
        )
        val stored = decided(draft, decider, now, rule.reviewBy != null)
        val decision = repository.transaction(decider.orgId) {
            insertRule(stored)
            record(signer, decider, now, before = null, after = stored)
        }
        signDecisions(listOfNotNull(decision))
        return stored
    }

    private companion object {
        const val RULE = "rule"
        const val GATE = "gate"
        const val WRITTEN = "written"
    }
}

/** Decides or edits a rule: a signed decision when what a decision covers changes. */
interface UpdateRuleUseCase {
    /**
     * Changes it.
     *
     * @param decider Who decides.
     * @param id The rule id.
     * @param patch The change.
     * @return The rule as it is now.
     * @throws RuleNotFoundException When the organization has no such rule.
     * @throws InvalidRuleException When a field is out of bounds.
     */
    suspend operator fun invoke(decider: Decider, id: String, patch: RulePatch): Rule
}

/**
 * [UpdateRuleUseCase] on the repository, the rule locked for the change.
 *
 * @param repository The rules.
 * @param signer Gives the rule's digest.
 * @param signDecisions Signs the decision once committed.
 * @param clock The time.
 */
class UpdateRuleUseCaseImpl(
    private val repository: RulesRepository,
    private val signer: DecisionSigner,
    private val signDecisions: SignDecisionsUseCase,
    private val clock: Clock,
) : UpdateRuleUseCase {
    override suspend fun invoke(decider: Decider, id: String, patch: RulePatch): Rule {
        val checked = checked(patch)
        val now = clock.now()
        val (rule, decision) = repository.transaction(decider.orgId) {
            val before = lockRule(id) ?: throw RuleNotFoundException()
            change(signer, decider, now, before, checked)
        }
        signDecisions(listOfNotNull(decision))
        return rule
    }

    private fun checked(patch: RulePatch) = patch.copy(
        statement = patch.statement?.let(RuleChecks::statement),
        reviewBy = patch.reviewBy?.let(RuleChecks::reviewBy),
        scope = patch.scope?.let(RuleChecks::scope),
        group = patch.group?.let(RuleChecks::group),
        // A blank reason removes it: "" says so to `change`, null keeps the rule's.
        why = patch.why?.let { RuleChecks.why(it) ?: "" },
        output = patch.output?.let(RuleChecks::output),
        gate = when (val change = patch.gate) {
            is GateChange.Replace -> GateChange.Replace(RuleChecks.gate(change.gate))
            else -> change
        },
    )
}

/** Decides several rules at once (the console's "accept the sure ones"). */
interface BulkUpdateRulesUseCase {
    /**
     * Sets the status of each rule found; an unknown id is skipped.
     *
     * @param decider Who decides.
     * @param ids The rules.
     * @param status `accepted`, `rejected` or `proposed`.
     * @return How many of the rules exist and now have this status.
     * @throws InvalidRuleException When there are too many ids, or the status is `edited`.
     */
    suspend operator fun invoke(decider: Decider, ids: List<String>, status: RuleStatus): Int

    /** The bound of one call. */
    companion object {
        /** The rules one call decides at most. */
        const val MAX_IDS: Int = 200
    }
}

/**
 * [BulkUpdateRulesUseCase]: one transaction and one signed decision per rule, so one failure leaves the others
 * decided and every decision has its receipt.
 *
 * @param repository The rules.
 * @param signer Gives the rules' digests.
 * @param signDecisions Signs each decision once committed.
 * @param clock The time.
 */
class BulkUpdateRulesUseCaseImpl(
    private val repository: RulesRepository,
    private val signer: DecisionSigner,
    private val signDecisions: SignDecisionsUseCase,
    private val clock: Clock,
) : BulkUpdateRulesUseCase {
    override suspend fun invoke(decider: Decider, ids: List<String>, status: RuleStatus): Int {
        if (ids.size > BulkUpdateRulesUseCase.MAX_IDS) {
            throw InvalidRuleException("At most ${BulkUpdateRulesUseCase.MAX_IDS} rules at once.")
        }
        if (status == RuleStatus.EDITED) throw InvalidRuleException("A rule is edited one at a time.")
        val now = clock.now()
        val changes = ids.distinct().mapNotNull { id ->
            repository.transaction(decider.orgId) {
                lockRule(id)?.let { change(signer, decider, now, it, RulePatch(status = status)) }
            }
        }
        signDecisions(changes.mapNotNull { it.second })
        return changes.size
    }
}

/** Deletes a rule: a signed decision. */
interface DeleteRuleUseCase {
    /**
     * Deletes it.
     *
     * @param decider Who decides.
     * @param id The rule id.
     * @throws RuleNotFoundException When the organization has no such rule.
     */
    suspend operator fun invoke(decider: Decider, id: String)
}

/**
 * [DeleteRuleUseCase] on the repository.
 *
 * @param repository The rules.
 * @param signer Gives the rule's digest.
 * @param signDecisions Signs the decision once committed.
 * @param clock The time.
 */
class DeleteRuleUseCaseImpl(
    private val repository: RulesRepository,
    private val signer: DecisionSigner,
    private val signDecisions: SignDecisionsUseCase,
    private val clock: Clock,
) : DeleteRuleUseCase {
    override suspend fun invoke(decider: Decider, id: String) {
        val now = clock.now()
        val decision = repository.transaction(decider.orgId) {
            val before = lockRule(id) ?: throw RuleNotFoundException()
            deleteRule(id)
            record(signer, decider, now, before = before, after = null)
        }
        signDecisions(listOfNotNull(decision))
    }
}

/**
 * Applies a checked patch to a locked rule, stores it and records its decision (BOUCLE, prototype `routes.ts`
 * `patchRule`): a new statement without a status makes the rule `edited`.
 *
 * @return The rule as it is now, and its decision when there is one.
 */
private suspend fun RulesTransaction.change(
    signer: DecisionSigner,
    decider: Decider,
    now: Instant,
    before: Rule,
    patch: RulePatch,
): Pair<Rule, Decision?> {
    val edited = patched(before, patch, now)
    val after = if (RuleDecisions.action(before, edited) == null) {
        edited
    } else {
        decided(edited, decider, now, patch.reviewBy != null)
    }
    updateRule(after)
    return after to record(signer, decider, now, before = before, after = after)
}

// The patch over the rule: what it leaves out is kept, an empty why removes it, a new statement alone edits the rule.
private fun patched(before: Rule, patch: RulePatch, now: Instant): Rule {
    val statement = patch.statement ?: before.statement
    val status = patch.status ?: if (statement != before.statement) RuleStatus.EDITED else before.status
    return before.copy(
        statement = statement,
        status = status,
        strength = patch.strength ?: before.strength,
        scope = patch.scope ?: before.scope,
        group = patch.group ?: before.group,
        why = whyOf(before.why, patch.why),
        output = patch.output ?: before.output,
        gate = when (val change = patch.gate) {
            GateChange.Keep -> before.gate
            GateChange.Remove -> null
            is GateChange.Replace -> change.gate
        },
        reviewBy = patch.reviewBy ?: before.reviewBy,
        updatedAt = now,
    )
}

private fun whyOf(before: String?, patched: String?): String? = when (patched) {
    null -> before
    "" -> null
    else -> patched
}

/**
 * Stamps a rule its decision: who and when, and for a decided rule its review date (90 days after the decision unless
 * one was given). A proposed rule has none of them.
 */
private fun decided(rule: Rule, decider: Decider, now: Instant, reviewGiven: Boolean): Rule = when (rule.status) {
    RuleStatus.ACCEPTED, RuleStatus.EDITED -> rule.copy(
        decidedBy = decider.name,
        decidedAt = now,
        reviewBy = if (reviewGiven) rule.reviewBy else RuleDecisions.reviewByOf(now),
    )
    RuleStatus.REJECTED -> rule.copy(decidedBy = decider.name, decidedAt = now, reviewBy = null)
    RuleStatus.PROPOSED -> rule.copy(decidedBy = null, decidedAt = null, reviewBy = null)
}
