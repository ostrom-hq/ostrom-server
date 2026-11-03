// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import kotlin.uuid.Uuid

/**
 * Every rule of an organization, for the readers that must see them all: the compiler's sources and the analysis
 * snapshot. Read a page of [PAGE] at a time by id, so that no query reads the whole table at once and none of them is
 * cut at a page: past 1 000 rules, the 1 400th counts as much as the first.
 */
object EveryRule {
    /** The rules one query reads. */
    const val PAGE: Int = 1_000

    /**
     * Every rule.
     *
     * @param repository The rules.
     * @param orgId The organization.
     * @return Them all, by group then id, as the list of the console.
     */
    suspend fun of(repository: RulesRepository, orgId: Uuid): List<Rule> =
        pages { after -> repository.rulesById(orgId, after, PAGE) }.sortedWith(compareBy({ it.group }, { it.id }))

    /**
     * Every rule that puts a gate on boundary actions and is not rejected.
     *
     * @param repository The rules.
     * @param orgId The organization.
     * @return Them all, by id.
     */
    suspend fun gated(repository: RulesRepository, orgId: Uuid): List<Rule> =
        pages { after -> repository.gatedRules(orgId, after, PAGE) }

    // Page after page, each after the last id of the one before, until a page that is not full.
    private suspend fun pages(read: suspend (after: String?) -> List<Rule>): List<Rule> {
        val all = mutableListOf<Rule>()
        var after: String? = null
        while (true) {
            val page = read(after)
            all += page
            if (page.size < PAGE) return all
            after = page.last().id
        }
    }
}
