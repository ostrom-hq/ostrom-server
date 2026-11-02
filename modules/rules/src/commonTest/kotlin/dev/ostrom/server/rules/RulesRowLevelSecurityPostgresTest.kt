// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.rules.domain.models.AnalysisFailure
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.models.RuleSource
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.RulesFixtures.orgA
import dev.ostrom.server.rules.fakes.RulesFixtures.orgB
import dev.ostrom.server.rules.infrastructure.PostgresAnalysisRepository
import dev.ostrom.server.rules.infrastructure.PostgresRulesRepository
import dev.ostrom.server.rules.infrastructure.RulesMigrations
import dev.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The rules tables under the serving role, as a deployment connects (neither superuser nor `BYPASSRLS`, owning
 * nothing): each organization reads and writes only its own rows, and without an organization no row at all.
 */
class RulesRowLevelSecurityPostgresTest {
    private val tables = listOf("rules_rules", "rules_decisions", "rules_gate_policy", "rules_analyses")

    private fun asServingRole(
        block: suspend (superuser: Driver, serving: PostgresRulesRepository, raw: Driver) -> Unit,
    ) = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // The owner migrates, as `ostrom-server migrate` does; the test database's driver is the serving role.
            MigrationRunnerImpl(database.owner, RecordingLogger()).migrate("rules", RulesMigrations)
            block(database.superuser, PostgresRulesRepository(database.driver), database.driver)
        } finally {
            database.close()
        }
    }

    private fun decision(id: String, orgId: kotlin.uuid.Uuid) = Decision(
        orgId = orgId,
        id = id,
        at = RulesFixtures.epoch,
        by = "lyes",
        subject = DecisionSubject("rule", "rule_1", "label"),
        action = "accepted",
        before = null,
        after = null,
        ruleDigest = "sha256:x",
    )

    @Test
    fun eachOrganizationSeesAndChangesOnlyItsOwnRules() = asServingRole { superuser, rules, _ ->
        rules.transaction(orgA) {
            insertRule(RulesFixtures.rule("rule_1"))
            insertDecision(decision("dec_a", orgA))
            putSelfApproval(SelfApprovalPolicy.NEVER, RulesFixtures.epoch)
        }
        rules.transaction(orgB) {
            insertRule(RulesFixtures.rule("rule_1", orgB, group = "B's"))
            insertDecision(decision("dec_b", orgB))
        }
        assertEquals(listOf("Shipping"), rules.rules(orgA, RuleQuery(), 10).map { it.group })
        assertEquals(listOf("B's"), rules.rules(orgB, RuleQuery(), 10).map { it.group })
        assertEquals(listOf("dec_a"), rules.decisions(orgA, DecisionQuery()).map { it.id })
        assertEquals(SelfApprovalPolicy.NEVER, rules.selfApproval(orgA))
        assertNull(rules.selfApproval(orgB))

        rules.markSigned(orgB, listOf("dec_a"))
        assertEquals(false, rules.decisions(orgA, DecisionQuery()).single().signed, "Org B cannot sign A's")
        rules.markSigned(orgA, emptyList())
        rules.markSigned(orgA, listOf("dec_a"))
        assertTrue(rules.decisions(orgA, DecisionQuery()).single().signed)

        rules.transaction(orgB) { deleteRule("rule_1") }
        assertEquals(1, rules.rules(orgA, RuleQuery(), 10).size, "Org B's delete leaves A's rule_1")

        listOf("rules_rules", "rules_decisions", "rules_gate_policy").forEach { table ->
            val count = superuser.fetchAll("SELECT count(*) AS n FROM $table").getOrThrow().single().get("n").asLong()
            assertTrue(count >= 1, "The superuser sees $table")
        }
    }

    @Test
    fun aRuleIsReadBackAsItWasWritten() = asServingRole { _, rules, _ ->
        val written = RulesFixtures.rule("rule_full", status = RuleStatus.ACCEPTED).copy(
            sources = listOf(RuleSource("pr", "#12", null), RuleSource("file", "CODEOWNERS", "https://x")),
            why = "Audit",
            reviewBy = "2027-01-28",
            decidedBy = "lyes",
            decidedAt = RulesFixtures.epoch,
            origin = null,
            gate = RuleGate(
                listOf(
                    RuleGateAction("push-main", "Push", listOf("git push"), listOf("ostrom-hq/web"), null),
                    RuleGateAction("custom", "Deploy", emptyList(), emptyList(), listOf("Bash")),
                ),
                listOf("lyes"),
                "Owner",
            ),
        )
        val actions = written.gate?.actions.orEmpty()
        val bare = RulesFixtures.rule("rule_bare").copy(gate = RuleGate(actions, emptyList(), null))
        rules.transaction(orgA) {
            insertRule(written)
            insertRule(bare)
        }
        assertEquals(written, rules.rule(orgA, "rule_full"))
        assertEquals(bare, rules.rule(orgA, "rule_bare"))
        rules.transaction(orgA) { updateRule(written.copy(statement = "Changed", gate = null)) }
        assertEquals(written.copy(statement = "Changed", gate = null), rules.rule(orgA, "rule_full"))
    }

    @Test
    fun theCompilerReadsOnlyItsOrganizationsGatesAndLastDecisions() = asServingRole { _, rules, _ ->
        val push = RuleGateAction("push-main", "Push", emptyList(), emptyList(), null)
        val gate = RuleGate(listOf(push), listOf("mia"), null)
        rules.transaction(orgA) {
            insertRule(RulesFixtures.rule("rule_b", status = RuleStatus.ACCEPTED).copy(gate = gate))
            insertRule(RulesFixtures.rule("rule_a").copy(gate = gate))
            insertRule(RulesFixtures.rule("rule_no", status = RuleStatus.REJECTED).copy(gate = gate))
            insertRule(RulesFixtures.rule("rule_plain", status = RuleStatus.ACCEPTED))
            insertDecision(decision("dec_1", orgA).copy(subject = DecisionSubject("rule", "rule_a", null)))
            insertDecision(
                decision("dec_2", orgA).copy(
                    at = RulesFixtures.epoch + 1.minutes,
                    subject = DecisionSubject("rule", "rule_a", null),
                ),
            )
            insertDecision(decision("dec_3", orgA).copy(subject = DecisionSubject("rule", "rule_b", null)))
            insertDecision(decision("dec_p", orgA).copy(subject = DecisionSubject("policy", "self-approval", null)))
        }
        rules.transaction(orgB) {
            insertRule(RulesFixtures.rule("rule_c", orgB, status = RuleStatus.ACCEPTED).copy(gate = gate))
            insertDecision(decision("dec_b", orgB))
        }
        assertEquals(listOf("rule_a", "rule_b"), rules.gatedRules(orgA).map { it.id }, "Rejected or gateless: no gate")
        assertEquals(listOf("rule_c"), rules.gatedRules(orgB).map { it.id })
        assertEquals(listOf("dec_2", "dec_3"), rules.lastRuleDecisions(orgA).map { it.id }, "The last one per rule")
        assertEquals(listOf("dec_b"), rules.lastRuleDecisions(orgB).map { it.id })
    }

    @Test
    fun eachOrganizationReadsAndLocksOnlyItsOwnRuns() = asServingRole { _, _, raw ->
        val runs = PostgresAnalysisRepository(raw)
        val failed = AnalysisRun(
            orgId = orgA,
            kind = AnalysisKind.ANALYSIS,
            id = "run_a",
            phase = AnalysisRun.FAILED,
            progress = 0.5,
            failure = AnalysisFailure("engine", "No engine.", RulesFixtures.epoch, "/api/v1/analysis/restart"),
            startedBy = "lyes",
            startedAt = RulesFixtures.epoch,
            updatedAt = RulesFixtures.epoch,
            items = listOf(JsonObject(mapOf("judged" to JsonPrimitive(true)))),
        )
        assertNull(runs.transaction(orgA) { lock(AnalysisKind.ANALYSIS) }, "Never started: an empty row")
        runs.transaction(orgA) { put(failed) }
        assertEquals(failed, runs.run(orgA, AnalysisKind.ANALYSIS), "Read back as written")
        val running = failed.copy(id = "run_b", phase = AnalysisRun.CONNECT, failure = null, items = listOf())
        runs.transaction(orgA) {
            assertEquals(failed, lock(AnalysisKind.ANALYSIS))
            put(running)
        }
        assertEquals(running, runs.run(orgA, AnalysisKind.ANALYSIS))
        assertNull(runs.run(orgA, AnalysisKind.REPLAY))
        assertNull(runs.run(orgB, AnalysisKind.ANALYSIS), "Org B sees none of A's runs")
        runs.transaction(orgB) { put(running.copy(orgId = orgB, id = "run_c")) }
        assertEquals(running, runs.run(orgA, AnalysisKind.ANALYSIS), "B's write never reaches A's row")
        assertNull(runs.run(orgB, AnalysisKind.ANALYSIS), "Without its row, B's update changes nothing")
    }

    @Test
    fun aRowOfAnotherOrganizationIsRefused() = asServingRole { _, rules, raw ->
        rules.transaction(orgA) { insertRule(RulesFixtures.rule("rule_1", orgB)) }
        assertEquals(orgA, rules.rule(orgA, "rule_1")?.orgId, "A transaction writes its own organization's row")
        assertNull(rules.rule(orgB, "rule_1"))
        val planted = Statement.create(
            "INSERT INTO rules_gate_policy (org_id, self_approval, updated_at) VALUES (:org, 'never', now())",
        ).bind("org", orgB)
        assertFailsWith<SQLError> { raw.withOrganization(orgA) { it.execute(planted).getOrThrow() } }
        assertNull(rules.selfApproval(orgB))
    }

    @Test
    fun withoutAnOrganizationNoRowIsVisible() = asServingRole { _, rules, raw ->
        rules.transaction(orgA) { insertRule(RulesFixtures.rule("rule_1")) }
        tables.forEach { table ->
            val count = raw.fetchAll("SELECT count(*) AS n FROM $table").getOrThrow().single().get("n").asLong()
            assertEquals(0L, count, "$table without ostrom.org_id")
        }
    }
}
