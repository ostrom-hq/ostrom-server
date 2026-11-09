// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.services.EveryRule
import run.ostrom.server.rules.fakes.RecordingLogger
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import run.ostrom.server.rules.fakes.RulesFixtures.orgB
import run.ostrom.server.rules.infrastructure.PostgresRulesRepository
import run.ostrom.server.rules.infrastructure.RulesCompilerSources
import run.ostrom.server.rules.infrastructure.RulesMigrations
import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The readers that must see every rule (the compiler's sources, the analysis snapshot) past 1 000 rules: 1 500 rules
 * of org A, read a page of 1 000 at a time by id, on the serving role under row level security. Rule 1 400, with a
 * gate, reaches the rules harness and the gates catalogue; and no page scans the table.
 */
class EveryRulePostgresTest {
    private val gate = RuleGate(
        actions = listOf(RuleGateAction("push-main", "Push to main", listOf("git push"), listOf("gate-demo"), null)),
        approvers = listOf("mia"),
        reason = null,
    )

    // The compiler's own sources are not read here: the rules come from the repository.
    private val defaults = object : CompilerSources {
        override suspend fun rules(orgId: Uuid): List<RuleDoc> = error("read from the rules")
        override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = error("read from the rules")
        override suspend fun repos(orgId: Uuid): List<String> = emptyList()
        override suspend fun settings(orgId: Uuid): CompilerSettings = CompilerSettings(prodApprover = "ops")
    }

    private fun withRepository(
        block: suspend (superuser: Driver, rules: PostgresRulesRepository, raw: Driver) -> Unit,
    ) = runTest(timeout = 5.minutes) {
        val database = TestDatabase.create() ?: return@runTest
        try {
            MigrationRunnerImpl(database.owner, RecordingLogger()).migrate("rules", RulesMigrations)
            block(database.superuser, PostgresRulesRepository(database.driver), database.driver)
        } finally {
            database.close()
        }
    }

    private fun id(n: Int) = "rule_${n.toString().padStart(4, '0')}"

    // 1 500 rules of org A, in groups that do not follow the ids; the first 1 200 and rule 1 400 carry an accepted
    // gate, rule 1 450 a rejected one. Org B has a rule of its own.
    private suspend fun seed(rules: PostgresRulesRepository) {
        rules.transaction(orgA) {
            (1..TOTAL).forEach { n ->
                val rule = RulesFixtures.rule(id(n), group = "Group ${(TOTAL - n) % GROUPS}")
                insertRule(
                    when {
                        n <= GATED || n == TARGET -> rule.copy(status = RuleStatus.ACCEPTED, gate = gate)
                        n == REJECTED -> rule.copy(status = RuleStatus.REJECTED, gate = gate)
                        else -> rule
                    },
                )
            }
        }
        rules.transaction(orgB) { insertRule(RulesFixtures.rule("rule_b", orgB).copy(gate = gate)) }
    }

    @Test
    fun theCompilerAndTheSnapshotSeeRule1400() = withRepository { _, rules, _ ->
        seed(rules)
        val every = EveryRule.of(rules, orgA)
        assertEquals(TOTAL, every.size, "Every rule, over two pages")
        assertEquals(TOTAL, every.map { it.id }.toSet().size, "Each once")
        assertEquals(every.sortedWith(compareBy({ it.group }, { it.id })), every, "By group, then id")
        assertTrue(every.none { it.orgId == orgB }, "Only org A's")

        val sources = RulesCompilerSources(rules, defaults)
        assertTrue(sources.rules(orgA).any { it.id == id(TARGET) }, "Rule 1 400 is in the rules harness")
        val gates = sources.ruleGates(orgA)
        assertEquals(GATED + 1, gates.size, "Every gated rule, rejected ones aside, over two pages")
        val target = gates.single { it.id == "rule:${id(TARGET)}" }
        assertEquals(WorkflowStatus.ACCEPTED, target.status, "Rule 1 400's gate is enforced")
        assertFalse(gates.any { it.id == "rule:${id(REJECTED)}" }, "A rejected rule gates nothing")
        assertEquals(listOf("rule:rule_b"), sources.ruleGates(orgB).map { it.id })
    }

    @Test
    fun aPageReadsItsOrganizationsRowsByAnIndex() = withRepository { superuser, rules, raw ->
        seed(rules)
        // Fixtures only: another organization's rows around, then the statistics, which no request writes. No `::`
        // cast and no colon: the driver would read `:x` as a named parameter.
        superuser.execute(
            "INSERT INTO rules_rules (org_id, id, statement, output, rule_group, scope, sources, strength, " +
                "confidence, status, found_by, found_at, updated_at, gate) SELECT CAST('$orgB' AS uuid), " +
                "'rule_x' || i, 'Seed', 'rule', 'Group ' || (i % 40), '[]', '[]', 'required', 0.8, 'accepted', " +
                "'analysis', now(), now(), CASE WHEN i % 2 = 0 THEN '{}' END FROM generate_series(1, 20000) AS i",
        ).getOrThrow()
        superuser.execute("VACUUM ANALYZE rules_rules").getOrThrow()

        suspend fun plan(sql: String): String = raw.withOrganization(orgA) { tx ->
            val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql")
                .bind("org", orgA).bind("limit", EveryRule.PAGE).bind("after", id(EveryRule.PAGE))
            tx.fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        val every = plan(PostgresRulesRepository.rulesByIdSql(after = true))
        val gated = plan(PostgresRulesRepository.gatedRulesSql(after = true))
        // Printed for the pull request's EXPLAIN ANALYZE.
        println("EXPLAIN ANALYZE every rule, the page after rule 1 000, 1 500 rules of org A among 21 500:\n$every")
        println("EXPLAIN ANALYZE gated rules, the page after rule 1 000:\n$gated")
        assertFalse("Seq Scan on rules_rules" in every, "The page scans the table:\n$every")
        assertFalse("Seq Scan on rules_rules" in gated, "The gated page scans the table:\n$gated")
    }

    private companion object {
        const val TOTAL = 1_500
        const val GATED = 1_200
        const val TARGET = 1_400
        const val REJECTED = 1_450
        const val GROUPS = 7
    }
}
