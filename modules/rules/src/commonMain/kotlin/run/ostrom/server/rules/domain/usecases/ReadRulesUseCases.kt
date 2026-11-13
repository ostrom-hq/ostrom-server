// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.usecases

import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionQuery
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleCursor
import run.ostrom.server.rules.domain.models.RuleNotFoundException
import run.ostrom.server.rules.domain.models.RulePage
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.repositories.RulesRepository
import kotlin.uuid.Uuid

/** Lists the rules of an organization, a page at a time. */
interface ListRulesUseCase {
    /**
     * Lists a page of them.
     *
     * @param orgId The organization.
     * @param query The filters, and where the page starts.
     * @param limit The page size, brought into 1 to [MAX_RULES].
     * @return The rules, by group then id, and where the next page starts when more match.
     */
    suspend operator fun invoke(orgId: Uuid, query: RuleQuery, limit: Int): RulePage

    /** The bound of a page. */
    companion object {
        /** The rules one page returns at most, and by default. */
        const val MAX_RULES: Int = 1_000
    }
}

/**
 * [ListRulesUseCase] on the repository: it reads one rule more than the page, which tells whether another follows.
 *
 * @param repository The rules.
 */
class ListRulesUseCaseImpl(private val repository: RulesRepository) : ListRulesUseCase {
    override suspend fun invoke(orgId: Uuid, query: RuleQuery, limit: Int): RulePage {
        val size = Pages.size(limit, ListRulesUseCase.MAX_RULES, ListRulesUseCase.MAX_RULES)
        // An `after` that names no rule of the organization (forged, or another's) is a 400, not an empty page.
        val read = Pages.checked("after", query.after, repository.rules(orgId, query, size + 1)) {
            repository.rule(orgId, it.id) != null
        }
        val items = read.take(size)
        return RulePage(items, if (read.size > size) RuleCursor.after(items.last()) else null)
    }
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
        // A `before` that names no decision of the organization (forged, or another's) is a 400, not an empty page.
        val read = repository.decisions(orgId, query.copy(limit = query.limit + 1))
        val rows = Pages.checked("before", query.before, read) { repository.decision(orgId, it) != null }
        val page = signDecisions(rows.take(query.limit))
        return page to if (rows.size > query.limit) page.last().id else null
    }
}
