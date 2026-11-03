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
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/rules` on 10 organizations of 1 000 rules each (the most an organization lists), in 20 groups: the whole
 * list, one group, one status. Each keeps the read budget (ReadBench.kt); its queries, plans and p95 are written to
 * `build/reports/perf/rules.txt`.
 */
class RulesReadsBenchTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val orgs = listOf(orgA, orgB) + List(ORGS - 2) { Uuid.random() }

    // Copies the rule the API wrote, under new ids, groups and organizations. No `::` cast and no colon at all: the
    // driver would read `:x` as a named parameter.
    private fun copiesOf(org: Uuid, from: Int) =
        "INSERT INTO rules_rules (org_id, id, statement, output, rule_group, scope, sources, why, strength, " +
            "confidence, status, found_by, found_at, review_by, decided_by, decided_at, origin, gate, updated_at) " +
            "SELECT CAST('$org' AS uuid), 'seed-' || i, statement || ' ' || i, output, 'Group ' || (i % 20), scope, " +
            "sources, why, strength, confidence, CASE WHEN i % 4 = 0 THEN 'proposed' ELSE status END, found_by, " +
            "found_at, review_by, decided_by, decided_at, origin, gate, updated_at FROM rules_rules, " +
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

        val measures = listOf(
            "GET /api/v1/rules (1 000 rules)" to "/api/v1/rules",
            "GET /api/v1/rules?group=Group 3" to "/api/v1/rules?group=Group%203",
            "GET /api/v1/rules?status=proposed" to "/api/v1/rules?status=proposed",
        ).map { (name, path) -> server.measureRead(name) { get(path) { bearerAuth("member-a") } } }
        writeReadReport("rules", measures)
        measures.forEach { it.assertWithinBudget(setOf("rules_rules")) }
    }

    private companion object {
        const val ORGS = 10
        const val RULES_PER_ORG = 1_000
    }
}
