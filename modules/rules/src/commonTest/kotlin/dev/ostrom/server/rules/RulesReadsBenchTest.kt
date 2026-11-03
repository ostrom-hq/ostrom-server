// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesCallers.orgA
import dev.ostrom.server.rules.fakes.RulesCallers.orgB
import dev.ostrom.server.rules.fakes.TestClock
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.presentation.NewRuleRequest
import dev.ostrom.server.rules.presentation.RulesPage
import dev.ostrom.server.testing.assertNoScan
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/rules` on 10 organizations of 1 000 rules each, in 20 groups, two thirds of them scoped to a repository:
 * a page of 100 and the next one, one group, a common status and a rare one, one repository as the CLI asks it (no
 * limit), and the whole list in one page (1 000, the default and the largest). Each call is checked for the rules it
 * returns and never scans the table; the pages of 100 at most are held to the read budget (ReadBench.kt). The two
 * reads of hundreds of rules run in 5 ms alone but queue past 50 ms at 20 calls on the CI's cores: their p95 is
 * reported, not held, until the default page is smaller. The queries, plans and p95 are written to
 * `build/reports/perf/rules.txt`.
 */
class RulesReadsBenchTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val orgs = listOf(orgA, orgB) + List(ORGS - 2) { Uuid.random() }

    // Copies the rule the API wrote, under new ids, groups and organizations: one in four proposed, one in a hundred
    // rejected, and two in three scoped to `acme/r<i % 5>`. No `::` cast and no colon at all: the driver would read
    // `:x` as a named parameter.
    private fun copiesOf(org: Uuid, from: Int) =
        "INSERT INTO rules_rules (org_id, id, statement, output, rule_group, scope, sources, why, strength, " +
            "confidence, status, found_by, found_at, review_by, decided_by, decided_at, origin, gate, updated_at) " +
            "SELECT CAST('$org' AS uuid), 'seed-' || i, statement || ' ' || i, output, 'Group ' || (i % $GROUPS), " +
            "CASE WHEN i % 3 = 0 THEN scope ELSE '[\"acme/r' || (i % 5) || '\"]' END, sources, why, strength, " +
            "confidence, CASE WHEN i % 4 = 0 THEN 'proposed' WHEN i % 100 = 1 THEN 'rejected' ELSE status END, " +
            "found_by, found_at, review_by, decided_by, decided_at, origin, gate, updated_at FROM rules_rules, " +
            "generate_series($from, ${RULES_PER_ORG - 1}) AS i WHERE org_id = CAST('$orgA' AS uuid)"

    @Test
    fun theRuleListKeepsItsBudget() = benchTest(
        modules,
        module { includes(RulesCallers.overrides(TestClock()), RulesCallers.gateSessions) },
    ) { server ->
        val created = server.call("POST", "/api/v1/rules", "admin-a", NewRuleRequest("Ship", "Releases"))
        assertEquals(HttpStatusCode.Created, created.status)
        val fixtures = server.database.superuser
        orgs.drop(1).forEach { fixtures.execute(copiesOf(it, 0)).getOrThrow() }
        fixtures.execute(copiesOf(orgA, 1)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE rules_rules").getOrThrow()

        val first = server.call("GET", "/api/v1/rules?limit=$PAGE", "member-a").body<RulesPage>()
        val after = checkNotNull(first.next).encodeURLParameter()
        // Path, the rules it returns (the seed above: rule i of orgA, the API's own being i = 0 with an empty scope).
        // The pages come first, held to the budget; the two long reads last.
        val reads = listOf(
            "/api/v1/rules?limit=$PAGE" to PAGE,
            "/api/v1/rules?limit=$PAGE&after=$after" to PAGE,
            "/api/v1/rules?group=Group%203" to RULES_PER_ORG / GROUPS,
            "/api/v1/rules?status=proposed&limit=$PAGE" to PAGE,
            "/api/v1/rules?status=rejected&limit=$PAGE" to RULES_PER_ORG / 100,
            "/api/v1/rules?repo=acme/r3" to (0 until RULES_PER_ORG).count { it % 3 == 0 || it % 5 == 3 },
            "/api/v1/rules" to RULES_PER_ORG,
        )
        val measures = reads.map { (path, rules) ->
            val page = server.call("GET", path, "member-a").body<RulesPage>()
            assertEquals(rules, page.items.size, path)
            server.measureRead("GET $path ($rules rules)") { get(path) { bearerAuth("member-a") } }
        }
        writeReadReport("rules", measures)
        measures.forEach { it.assertNoScan(setOf("rules_rules")) }
        measures.dropLast(LONG_READS).forEach { it.assertWithinBudget(setOf("rules_rules")) }
    }

    private companion object {
        const val ORGS = 10
        const val RULES_PER_ORG = 1_000
        const val GROUPS = 20
        const val LONG_READS = 2
        const val PAGE = 100
    }
}
