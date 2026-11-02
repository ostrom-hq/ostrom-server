// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.usecases

import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleNotFoundException
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import kotlin.uuid.Uuid

/** Lists the rules of an organization. */
interface ListRulesUseCase {
    /**
     * Lists them.
     *
     * @param orgId The organization.
     * @param query The filters.
     * @return The rules, by group then id; at most [MAX_RULES].
     */
    suspend operator fun invoke(orgId: Uuid, query: RuleQuery): List<Rule>

    /** The bound of a list. */
    companion object {
        /** The rules one list returns at most: an organization's whole set fits. */
        const val MAX_RULES: Int = 1_000
    }
}

/**
 * [ListRulesUseCase] on the repository.
 *
 * @param repository The rules.
 */
class ListRulesUseCaseImpl(private val repository: RulesRepository) : ListRulesUseCase {
    override suspend fun invoke(orgId: Uuid, query: RuleQuery): List<Rule> =
        repository.rules(orgId, query, ListRulesUseCase.MAX_RULES)
}

/** Reads one rule. */
interface GetRuleUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @param id The rule id.
     * @return The rule.
     * @throws RuleNotFoundException When the organization has no such rule.
     */
    suspend operator fun invoke(orgId: Uuid, id: String): Rule
}

/**
 * [GetRuleUseCase] on the repository.
 *
 * @param repository The rules.
 */
class GetRuleUseCaseImpl(private val repository: RulesRepository) : GetRuleUseCase {
    override suspend fun invoke(orgId: Uuid, id: String): Rule =
        repository.rule(orgId, id) ?: throw RuleNotFoundException()
}

/** Lists the signed decisions of an organization, newest first. */
interface ListDecisionsUseCase {
    /**
     * Lists one page.
     *
     * @param orgId The organization.
     * @param query The filters and the cursor.
     * @return The page and the cursor of the next one, or null on the last page.
     */
    suspend operator fun invoke(orgId: Uuid, query: DecisionQuery): Pair<List<Decision>, String?>
}

/**
 * [ListDecisionsUseCase] on the repository: one more row than the page tells whether another page follows, and a
 * decision whose receipt failed to sign is signed now.
 *
 * @param repository The decisions.
 * @param signDecisions Signs those not signed yet.
 */
class ListDecisionsUseCaseImpl(
    private val repository: RulesRepository,
    private val signDecisions: SignDecisionsUseCase,
) : ListDecisionsUseCase {
    override suspend fun invoke(orgId: Uuid, query: DecisionQuery): Pair<List<Decision>, String?> {
        val rows = repository.decisions(orgId, query.copy(limit = query.limit + 1))
        val page = signDecisions(rows.take(query.limit))
        return page to if (rows.size > query.limit) page.last().id else null
    }
}
