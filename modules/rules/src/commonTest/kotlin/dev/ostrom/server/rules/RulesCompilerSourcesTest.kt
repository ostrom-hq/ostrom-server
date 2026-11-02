// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.CompilerSettings
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.RuleDoc
import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleSource
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.fakes.FakeRulesRepository
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.infrastructure.RulesCompilerSources
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class RulesCompilerSourcesTest {
    private val org = RulesFixtures.orgA
    private val repository = FakeRulesRepository()
    private val defaults = object : CompilerSources {
        override suspend fun rules(orgId: Uuid): List<RuleDoc> = error("read from the rules")
        override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = error("read from the rules")
        override suspend fun repos(orgId: Uuid): List<String> = listOf("ostrom-hq/gate-demo")
        override suspend fun settings(orgId: Uuid): CompilerSettings = CompilerSettings(prodApprover = "ops")
    }
    private val sources = RulesCompilerSources(repository, defaults)

    private fun gate(reason: String?, approvers: List<String>) = RuleGate(
        actions = listOf(RuleGateAction("push-main", "Push to main", listOf("git push"), listOf("gate-demo"), null)),
        approvers = approvers,
        reason = reason,
    )

    private fun decision(id: String, ruleId: String, minutes: Int, signed: Boolean, kind: String = "rule") = Decision(
        orgId = org,
        id = id,
        at = RulesFixtures.epoch + minutes.minutes,
        by = "lyes",
        subject = DecisionSubject(kind, ruleId, null),
        action = "accepted",
        before = null,
        after = null,
        signed = signed,
    )

    @Test
    fun theHarnessReadsEveryRuleAsStored() = runTest {
        val rule = RulesFixtures.rule("rule_a", status = RuleStatus.ACCEPTED, scope = listOf("gate-demo"))
            .copy(sources = listOf(RuleSource("file", "CONTRIBUTING.md", null)))
        repository.rules[org to rule.id] = rule
        val doc = sources.rules(org).single()
        assertEquals("rule_a", doc.id)
        assertEquals(listOf("gate-demo"), doc.scope)
        assertEquals(listOf("CONTRIBUTING.md"), doc.sourceRefs)
        assertEquals("accepted", doc.status)
        assertEquals("required", doc.strength)
        assertEquals(0.8, doc.confidence)
        assertEquals("Shipping", doc.group)
        assertEquals("rule_a", doc.raw.getValue("id").toString().trim('"'))
        assertEquals(true, doc.decided)
    }

    @Test
    fun theHarnessCitesOnlyTheSignedReceiptOfEachRulesLastDecision() = runTest {
        repository.decisions += decision("dec_1", "rule_a", 1, signed = true)
        repository.decisions += decision("dec_2", "rule_a", 2, signed = true)
        repository.decisions += decision("dec_3", "rule_b", 1, signed = false)
        repository.decisions += decision("dec_4", "self-approval", 3, signed = true, kind = "policy")
        val docs = sources.ruleDecisions(org).associateBy { it.subjectId }
        assertEquals(setOf("rule_a", "rule_b"), docs.keys)
        assertEquals("rcpt_dec_2", docs.getValue("rule_a").receiptId)
        assertEquals("2026-10-30T12:02:00.000Z", docs.getValue("rule_a").at)
        assertEquals("rule", docs.getValue("rule_a").subjectKind)
        assertEquals("lyes", docs.getValue("rule_a").by)
        assertEquals("dec_2", docs.getValue("rule_a").id)
        assertNull(docs.getValue("rule_b").receiptId, "Not signed yet: nothing to verify")
    }

    @Test
    fun repositoriesAndSettingsStayTheCompilers() = runTest {
        assertEquals(listOf("ostrom-hq/gate-demo"), sources.repos(org))
        assertEquals("ops", sources.settings(org).prodApprover)
    }

    @Test
    fun aRuleWithAGateIsAOneGateWorkflowEnforcedOnceDecided() = runTest {
        val accepted = RulesFixtures.rule("rule_a", status = RuleStatus.ACCEPTED)
            .copy(gate = gate("Mia owns releases", listOf("mia")), decidedBy = "lyes")
        repository.rules[org to accepted.id] = accepted
        val workflow = sources.ruleGates(org).single()
        assertEquals("rule:rule_a", workflow.id)
        assertEquals(accepted.statement, workflow.name)
        assertEquals("Shipping", workflow.domain)
        assertEquals(WorkflowStatus.ACCEPTED, workflow.status)
        assertEquals("lyes", workflow.updatedBy)
        assertEquals("2026-10-30T12:00:00.000Z", workflow.foundAt)
        val node = workflow.nodes.single()
        assertEquals(RulesCompilerSources.NODE, node.id)
        assertEquals(WorkflowNodeKind.GATE, node.kind)
        assertEquals(listOf("mia"), node.approvers)
        assertEquals("Mia owns releases", node.approverReason)
        assertEquals(listOf("rule_a"), node.ruleIds)
        val action = node.actions.orEmpty().single()
        assertEquals("push-main", action.cls)
        assertEquals("Push to main", action.label)
        assertEquals(listOf("git push"), action.patterns)
        assertEquals(listOf("gate-demo"), action.repos)
        assertNull(action.tools)
    }

    @Test
    fun aProposedRulesGateIsAProposedWorkflowAndItsReasonNamesTheRule() = runTest {
        val proposed = RulesFixtures.rule("rule_b").copy(gate = gate(null, listOf("mia")))
        val nobody = RulesFixtures.rule("rule_c", status = RuleStatus.EDITED).copy(gate = gate(null, emptyList()))
        repository.rules[org to proposed.id] = proposed
        repository.rules[org to nobody.id] = nobody
        val workflows = sources.ruleGates(org).associateBy { it.id }
        assertEquals(WorkflowStatus.PROPOSED, workflows.getValue("rule:rule_b").status)
        assertEquals("approvers of the rule", workflows.getValue("rule:rule_b").nodes.single().approverReason)
        assertEquals(WorkflowStatus.ACCEPTED, workflows.getValue("rule:rule_c").status)
        assertNull(
            workflows.getValue("rule:rule_c").nodes.single().approverReason,
            "No approver named: the catalogue says the production approver answers",
        )
    }

    @Test
    fun aRuleTheRepositoryReturnsWithoutAGateGatesNothing() = runTest {
        val gated = RulesFixtures.rule("rule_a", status = RuleStatus.ACCEPTED).copy(gate = gate(null, listOf("mia")))
        val loose: RulesRepository = object : RulesRepository by repository {
            override suspend fun gatedRules(orgId: Uuid): List<Rule> = listOf(RulesFixtures.rule("rule_x"), gated)
        }
        assertEquals(listOf("rule:rule_a"), RulesCompilerSources(loose, defaults).ruleGates(org).map { it.id })
    }
}
