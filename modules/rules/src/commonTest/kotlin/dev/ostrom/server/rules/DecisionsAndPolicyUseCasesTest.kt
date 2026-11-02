// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.domain.usecases.GetGatePolicyUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.ListDecisionsUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.SetGatePolicyUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.SignDecisionsUseCaseImpl
import dev.ostrom.server.rules.fakes.FakeDecisionSigner
import dev.ostrom.server.rules.fakes.RulesBench
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.RulesFixtures.lyes
import dev.ostrom.server.rules.fakes.RulesFixtures.orgA
import dev.ostrom.server.rules.fakes.RulesFixtures.orgB
import dev.ostrom.server.rules.infrastructure.RulesGatePolicies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DecisionsAndPolicyUseCasesTest {
    private val bench = RulesBench()
    private val getPolicy = GetGatePolicyUseCaseImpl(bench.repository)
    private val setPolicy = SetGatePolicyUseCaseImpl(bench.repository, bench.signDecisions, bench.clock)

    private fun decision(id: String, signed: Boolean = true, orgId: kotlin.uuid.Uuid = orgA) = Decision(
        orgId = orgId,
        id = id,
        at = RulesFixtures.epoch,
        by = "lyes",
        subject = DecisionSubject("rule", "rule_1", null),
        action = "accepted",
        before = null,
        after = null,
        signed = signed,
    )

    @Test
    fun anOrganizationThatNeverChoseLetsTheSoleApproverApprove() = runTest {
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, getPolicy(orgA))
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, RulesGatePolicies(getPolicy).selfApproval(orgA))
    }

    @Test
    fun settingThePolicySignsADecisionAndTheGatesApplyIt() = runTest {
        assertEquals(SelfApprovalPolicy.NEVER, setPolicy(lyes, SelfApprovalPolicy.NEVER))
        assertEquals(SelfApprovalPolicy.NEVER, RulesGatePolicies(getPolicy).selfApproval(orgA))
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, RulesGatePolicies(getPolicy).selfApproval(orgB), "Only org A's")
        val decision = bench.repository.decisions.single()
        assertEquals(DecisionSubject("policy", "self-approval", "Self-approval"), decision.subject)
        assertEquals("edited", decision.action)
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"sole-approver"}""").jsonObject, decision.before)
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"never"}""").jsonObject, decision.after)
        assertNull(decision.ruleDigest, "A policy names no rule")
        assertTrue(decision.signed)
    }

    @Test
    fun settingThePolicyInForceRecordsNothing() = runTest {
        setPolicy(lyes, SelfApprovalPolicy.SOLE_APPROVER)
        setPolicy(lyes, SelfApprovalPolicy.NEVER)
        setPolicy(lyes, SelfApprovalPolicy.NEVER)
        assertEquals(1, bench.repository.decisions.size)
        assertEquals(SelfApprovalPolicy.NEVER, bench.repository.policies[orgA])
    }

    @Test
    fun decisionsArePagedNewestFirstWithACursor() = runTest {
        bench.repository.decisions += (1..3).map { decision("dec_$it") }
        val list = ListDecisionsUseCaseImpl(bench.repository, bench.signDecisions)
        val (first, next) = list(orgA, DecisionQuery(limit = 2))
        assertEquals(listOf("dec_3", "dec_2"), first.map { it.id })
        assertEquals("dec_2", next)
        val (all, end) = list(orgA, DecisionQuery())
        assertEquals(3, all.size)
        assertNull(end)
        assertEquals(DecisionQuery.PAGE, DecisionQuery().limit)
    }

    @Test
    fun aDecisionLeftUnsignedIsSignedAtTheNextRead() = runTest {
        bench.repository.decisions += decision("dec_1", signed = false)
        bench.repository.decisions += decision("dec_2")
        val (items, _) = ListDecisionsUseCaseImpl(bench.repository, bench.signDecisions)(orgA, DecisionQuery())
        assertTrue(items.all { it.signed })
        assertEquals(listOf("dec_1"), bench.signer.signed.map { it.id }, "A signed one is not signed again")
        assertTrue(bench.repository.decisions.all { it.signed })
    }

    @Test
    fun signingNothingWritesNothing() = runTest {
        val signed = listOf(decision("dec_1"))
        assertSame(signed, bench.signDecisions(signed))
        assertEquals(emptyList(), bench.signDecisions(emptyList()))
        bench.signer.failing = true
        val unsigned = listOf(decision("dec_2", signed = false))
        assertEquals(unsigned, bench.signDecisions(unsigned))
        assertEquals(1, bench.logger.events.size)
    }

    @Test
    fun aCancelledSigningIsNeverSwallowed() = runTest {
        val cancelling = object : dev.ostrom.server.rules.domain.services.DecisionSigner by FakeDecisionSigner() {
            override suspend fun sign(decision: Decision): Unit = throw CancellationException("stopped")
        }
        val sign = SignDecisionsUseCaseImpl(bench.repository, cancelling, bench.logger)
        assertFailsWith<CancellationException> { sign(listOf(decision("dec_1", signed = false))) }
        assertTrue(bench.logger.events.isEmpty())
    }
}
