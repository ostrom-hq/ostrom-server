// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.rules.domain.models.RuleCursor
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.usecases.ListRulesUseCase
import run.ostrom.server.rules.domain.usecases.ListRulesUseCaseImpl
import run.ostrom.server.rules.fakes.RecordingLogger
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import run.ostrom.server.rules.fakes.RulesFixtures.orgB
import run.ostrom.server.rules.infrastructure.PostgresRulesRepository
import run.ostrom.server.rules.infrastructure.RulesMigrations
import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * `GET /rules` past 1 000 rules (audit of the reads, B2): the repository filter is in the SQL, before the limit, so an
 * organization with more rules than one page still gets every rule of a repository; the pages walk every rule once;
 * all on the serving role under row level security, and by an index, never a scan of the whole table.
 */
class RulesListPostgresTest {
    private fun withRepository(
        block: suspend (superuser: Driver, rules: PostgresRulesRepository, raw: Driver) -> Unit,
    ) = runTest(timeout = 5.minutes) {
        val database = TestDatabase.create() ?: return@runTest
        try {
            database.migrationRunner(RecordingLogger()).migrate("rules", RulesMigrations)
            block(database.superuser, PostgresRulesRepository(database.driver), database.driver)
        } finally {
            database.close()
        }
    }

    // 1 500 rules in org A: 1 400 of another repository sort first (group `Alpha`), then those `acme/web` covers.
    private suspend fun seed(rules: PostgresRulesRepository) = rules.transaction(orgA) {
        repeat(OTHERS) { insertRule(RulesFixtures.rule("rule_a$it", group = "Alpha", scope = listOf("acme/other"))) }
        repeat(NAMED) { insertRule(RulesFixtures.rule("rule_n$it", group = "Zeta", scope = listOf("ACME/Web"))) }
        repeat(PATHS) { insertRule(RulesFixtures.rule("rule_p$it", group = "Zeta", scope = listOf("acme/web/src"))) }
        repeat(UNSCOPED) { insertRule(RulesFixtures.rule("rule_u$it", group = "Zeta")) }
        // Neither another repository whose name starts the same, nor a pattern: `_` is a letter here.
        repeat(LOOKALIKES) {
            insertRule(RulesFixtures.rule("rule_l$it", group = "Zeta", scope = listOf("acme/web-app")))
        }
        insertRule(RulesFixtures.rule("rule_x", group = "Zeta", scope = listOf("acme/wxb")))
    }

    @Test
    fun aRepositoryKeepsAllItsRulesPastAThousand() = withRepository { _, rules, _ ->
        seed(rules)
        rules.transaction(orgB) { insertRule(RulesFixtures.rule("rule_b", orgB, group = "Zeta")) }
        val limit = ListRulesUseCase.MAX_RULES

        assertEquals(TOTAL, OTHERS + NAMED + PATHS + UNSCOPED + LOOKALIKES + 1, "1 500 rules")
        assertEquals(limit, rules.rules(orgA, RuleQuery(), limit).size, "A page without filter stops at the limit")
        val web = rules.rules(orgA, RuleQuery(repo = "acme/web"), limit).map { it.id }
        assertEquals(NAMED + PATHS + UNSCOPED, web.size, "Every rule acme/web covers, though 1 400 others sort first")
        assertEquals(web.sorted(), web, "In the console's order")
        assertFalse(web.any { it.startsWith("rule_a") || it.startsWith("rule_l") || it == "rule_x" || it == "rule_b" })
        assertEquals(web, rules.rules(orgA, RuleQuery(repo = "ACME/WEB"), limit).map { it.id }, "Case ignored")
        assertEquals(UNSCOPED, rules.rules(orgA, RuleQuery(repo = "acme/w_b"), limit).size, "No pattern")
        val other = rules.rules(orgA, RuleQuery(repo = "acme/other", group = "Zeta"), limit)
        assertEquals(UNSCOPED, other.size, "With the group, only the unscoped rules of Zeta")
        assertEquals(
            emptyList(),
            rules.rules(orgA, RuleQuery(repo = "acme/web", status = RuleStatus.ACCEPTED), limit),
            "With the status",
        )
        // Org B reads its own unscoped rule only.
        assertEquals(listOf("rule_b"), rules.rules(orgB, RuleQuery(repo = "acme/web"), limit).map { it.id })
    }

    @Test
    fun thePagesWalkEveryRuleOnce() = withRepository { _, rules, _ ->
        seed(rules)
        val list = ListRulesUseCaseImpl(rules)
        val first = list(orgA, RuleQuery(), ListRulesUseCase.MAX_RULES)
        assertEquals(ListRulesUseCase.MAX_RULES, first.items.size)
        val second = list(orgA, RuleQuery(after = assertNotNull(first.next)), ListRulesUseCase.MAX_RULES)
        assertEquals(TOTAL - ListRulesUseCase.MAX_RULES, second.items.size)
        assertNull(second.next, "Two pages hold 1 500 rules")
        assertEquals(TOTAL, (first.items + second.items).map { it.id }.toSet().size, "Each rule once")

        // A repository's rules in pages of 30, after the 1 400 others: the same rules as one page, in the same order.
        val seen = mutableListOf<String>()
        var after: RuleCursor? = null
        do {
            val page = list(orgA, RuleQuery(repo = "acme/web", after = after), PAGE)
            assertTrue(page.items.size <= PAGE)
            seen += page.items.map { it.id }
            after = page.next
        } while (after != null)
        val whole = rules.rules(orgA, RuleQuery(repo = "acme/web"), ListRulesUseCase.MAX_RULES).map { it.id }
        assertEquals(whole, seen)
    }

    @Test
    fun theRepositoryFilterReadsTheOrganizationsRowsByAnIndex() = withRepository { superuser, rules, raw ->
        seed(rules)
        // Fixtures only: another organization's rows around, then the statistics, which no request writes. No `::`
        // cast and no colon: the driver would read `:x` as a named parameter.
        superuser.execute(
            "INSERT INTO rules_rules (org_id, id, statement, output, rule_group, scope, sources, strength, " +
                "confidence, status, found_by, found_at, updated_at) SELECT CAST('$orgB' AS uuid), 'rule_' || i, " +
                "'Seed', 'rule', 'Group ' || (i % 40), '[\"acme/web\"]', '[]', 'required', 0.8, 'proposed', " +
                "'analysis', now(), now() FROM generate_series(1, 20000) AS i",
        ).getOrThrow()
        superuser.execute("VACUUM ANALYZE rules_rules").getOrThrow()

        suspend fun plan(query: RuleQuery): String = raw.withOrganization(orgA) { tx ->
            val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${PostgresRulesRepository.rulesSql(query)}")
                .bind("org", orgA).bind("limit", ListRulesUseCase.MAX_RULES + 1)
                .bind("repo", "acme/web").bind("under", "acme/web/")
            query.after?.let { statement.bind("after_group", it.group).bind("after_id", it.id) }
            tx.fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        val filtered = plan(RuleQuery(repo = "acme/web"))
        val paged = plan(RuleQuery(repo = "acme/web", after = RuleCursor("Alpha", "rule_a999")))
        // Printed for the pull request's EXPLAIN ANALYZE.
        println("EXPLAIN ANALYZE GET /rules?repo=acme/web, 1 500 rules of org A among 21 500:\n$filtered")
        println("EXPLAIN ANALYZE GET /rules?repo=acme/web&after=…, the same rows:\n$paged")
        assertFalse("Seq Scan on rules_rules" in filtered, "The filter scans the table:\n$filtered")
        assertFalse("Seq Scan on rules_rules" in paged, "The page scans the table:\n$paged")
    }

    private companion object {
        const val OTHERS = 1_400
        const val NAMED = 50
        const val PATHS = 20
        const val UNSCOPED = 10
        const val LOOKALIKES = 19
        const val TOTAL = 1_500
        const val PAGE = 30
    }
}
