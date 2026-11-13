// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.gates.infrastructure.DefaultGatePolicies
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionQuery
import run.ostrom.server.rules.domain.models.DecisionSubject
import run.ostrom.server.rules.domain.usecases.GetGatePolicyUseCaseImpl
import run.ostrom.server.rules.domain.usecases.ListDecisionsUseCaseImpl
import run.ostrom.server.rules.domain.usecases.SetGatePolicyUseCaseImpl
import run.ostrom.server.rules.domain.usecases.SignDecisionsUseCaseImpl
import run.ostrom.server.rules.fakes.FakeDecisionSigner
import run.ostrom.server.rules.fakes.RulesBench
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.lyes
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import run.ostrom.server.rules.fakes.RulesFixtures.orgB
import run.ostrom.server.rules.infrastructure.RulesGatePolicies
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

    // One default, never (S2-15, decided on 02/11): the rules module, which the server binds, and the gates module's
    // fail-closed port read the same one, so no organization that never chose lets anyone approve their own action.
    @Test
    fun anOrganizationThatNeverChoseNeverApprovesItsOwnActionOnEitherSide() = runTest {
        assertEquals(SelfApprovalPolicy.NEVER, SelfApprovalPolicy.DEFAULT)
        assertEquals(SelfApprovalPolicy.DEFAULT, getPolicy(orgA))
        assertEquals(SelfApprovalPolicy.DEFAULT, RulesGatePolicies(getPolicy).selfApproval(orgA))
        assertEquals(SelfApprovalPolicy.DEFAULT, DefaultGatePolicies().selfApproval(orgA))
    }

    @Test
    fun settingThePolicySignsADecisionAndTheGatesApplyIt() = runTest {
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, setPolicy(lyes, SelfApprovalPolicy.SOLE_APPROVER))
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, RulesGatePolicies(getPolicy).selfApproval(orgA))
        assertEquals(SelfApprovalPolicy.NEVER, RulesGatePolicies(getPolicy).selfApproval(orgB), "Only org A's")
        val decision = bench.repository.decisions.single()
        assertEquals(DecisionSubject("policy", "self-approval", "Self-approval"), decision.subject)
        assertEquals("edited", decision.action)
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"never"}""").jsonObject, decision.before)
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"sole-approver"}""").jsonObject, decision.after)
        assertNull(decision.ruleDigest, "A policy names no rule")
        assertTrue(decision.signed)
    }

    @Test
    fun settingThePolicyInForceRecordsNothing() = runTest {
        setPolicy(lyes, SelfApprovalPolicy.SOLE_APPROVER)
        setPolicy(lyes, SelfApprovalPolicy.SOLE_APPROVER)
        assertEquals(1, bench.repository.decisions.size)
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, bench.repository.policies[orgA])
    }

    // Choosing the default explicitly is a decision too: the audit shows that an admin chose `never`, and when.
    @Test
    fun anOrganizationsFirstChoiceIsRecordedEvenWhenItIsTheDefault() = runTest {
        setPolicy(lyes, SelfApprovalPolicy.NEVER)
        val first = bench.repository.decisions.single()
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"never"}""").jsonObject, first.before)
        assertEquals(Json.parseToJsonElement("""{"selfApproval":"never"}""").jsonObject, first.after)
        setPolicy(lyes, SelfApprovalPolicy.NEVER)
        assertEquals(1, bench.repository.decisions.size, "Choosing it again records nothing")
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
        assertEquals(listOf("dec_1"), list(orgA, DecisionQuery(before = "dec_2")).first.map { it.id })
        assertEquals(emptyList(), list(orgA, DecisionQuery(before = "dec_1")).first, "The end of the list")
        // A decision the organization does not hold: forged, or another organization's.
        assertFailsWith<InvalidPageException> { list(orgA, DecisionQuery(before = "dec_9")) }
        assertFailsWith<InvalidPageException> { list(orgB, DecisionQuery(before = "dec_1")) }
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
        val cancelling = object : run.ostrom.server.rules.domain.services.DecisionSigner by FakeDecisionSigner() {
            override suspend fun sign(decision: Decision): Unit = throw CancellationException("stopped")
        }
        val sign = SignDecisionsUseCaseImpl(bench.repository, cancelling, bench.logger)
        assertFailsWith<CancellationException> { sign(listOf(decision("dec_1", signed = false))) }
        assertTrue(bench.logger.events.isEmpty())
    }
}
