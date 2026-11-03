// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.services.EveryRule
import dev.ostrom.server.rules.fakes.FakeRulesRepository
import dev.ostrom.server.rules.fakes.RulesFixtures
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EveryRuleTest {
    private val org = RulesFixtures.orgA

    private fun repository(count: Int, gated: Boolean = false) = FakeRulesRepository().apply {
        (1..count).forEach { n ->
            val id = "rule_${n.toString().padStart(5, '0')}"
            val rule = RulesFixtures.rule(id, group = "Group ${count - n}", status = RuleStatus.ACCEPTED)
            rules[org to id] = if (gated) rule.copy(gate = RuleGate(emptyList(), emptyList(), null)) else rule
        }
    }

    @Test
    fun noRuleIsAnEmptyList() = runTest {
        assertEquals(emptyList(), EveryRule.of(repository(0), org))
        assertEquals(emptyList(), EveryRule.gated(repository(0), org))
    }

    @Test
    fun exactlyOnePageReadsAnEmptyOneAfter() = runTest {
        assertEquals(EveryRule.PAGE, EveryRule.of(repository(EveryRule.PAGE), org).size)
        assertEquals(EveryRule.PAGE, EveryRule.gated(repository(EveryRule.PAGE, gated = true), org).size)
    }

    @Test
    fun manyPagesKeepEveryRuleOnceByGroupThenId() = runTest {
        val count = EveryRule.PAGE * 2 + 1
        val every = EveryRule.of(repository(count), org)
        assertEquals(count, every.map { it.id }.toSet().size)
        assertEquals(every.sortedWith(compareBy({ it.group }, { it.id })), every)
        val gated = EveryRule.gated(repository(count, gated = true), org)
        assertEquals(count, gated.size)
        assertEquals(gated.sortedBy { it.id }, gated, "The gated rules by id")
    }
}
