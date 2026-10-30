// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.database.withOrganization
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
import dev.ostrom.server.rules.infrastructure.PostgresRulesRepository
import dev.ostrom.server.rules.infrastructure.RulesMigrations
import dev.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules tables under the serving role, as a deployment connects (neither superuser nor `BYPASSRLS`, owning
 * nothing): each organization reads and writes only its own rows, and without an organization no row at all.
 */
class RulesRowLevelSecurityPostgresTest {
    private val tables = listOf("rules_rules", "rules_decisions", "rules_gate_policy")

    private fun asServingRole(block: suspend (owner: Driver, serving: PostgresRulesRepository, raw: Driver) -> Unit) =
        runTest {
            val database = TestDatabase.create() ?: return@runTest
            try {
                MigrationRunnerImpl(database.driver, RecordingLogger()).migrate("rules", RulesMigrations)
                database.withRole(poolSize = 1) { role, serving ->
                    tables.forEach {
                        database.driver.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON $it TO $role").getOrThrow()
                    }
                    block(database.driver, PostgresRulesRepository(serving), serving)
                }
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
    fun eachOrganizationSeesAndChangesOnlyItsOwnRules() = asServingRole { owner, rules, _ ->
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
            val count = owner.fetchAll("SELECT count(*) AS n FROM $table").getOrThrow().single().get("n").asLong()
            assertTrue(count >= 1, "The owner, a superuser in tests, sees $table")
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
