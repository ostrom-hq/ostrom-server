// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateKeys
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.OTHER_ORG
import dev.ostrom.server.gates.fakes.OTHER_SESSION
import dev.ostrom.server.gates.fakes.SHA_A
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GetGateAndRulesTest {
    @Test
    fun theHookReadsOnlyItsOwnGates() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())

        assertEquals(view.gate.id, bench.get(agent(), view.gate.id).gate.id)
        assertEquals(listOf("rev-b"), bench.get(user("anyone"), view.gate.id).waitingFor)
        assertFailsWith<NotYourGateException> { bench.get(agent(OTHER_SESSION), view.gate.id) }
        assertFailsWith<GateNotFoundException> { bench.get(user("rev-b", OTHER_ORG), view.gate.id) }
        assertFailsWith<GateNotFoundException> { bench.get(user("rev-b"), Uuid.random()) }
    }

    @Test
    fun aReadNeverSignsAPendingOrAlreadySignedGate() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        bench.get(user("rev-b"), view.gate.id)
        assertTrue(bench.signer.signed.isEmpty(), "Nothing to sign while pending")

        bench.answer(view, "rev-b", Answer.YES)
        bench.get(user("rev-b"), view.gate.id)
        assertEquals(1, bench.signer.signed.size, "Signed once, at the decision")
    }

    @Test
    fun aGateDecidedWithoutDateIsNotSigned() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val odd = view.gate.copy(status = GateStatus.APPROVED)
        assertNull(bench.receipts.ensureSigned(odd).receiptId)
        val datedButPending = view.gate.copy(decidedAt = bench.clock.now)
        assertNull(bench.receipts.ensureSigned(datedButPending).receiptId)
        assertTrue(bench.signer.signed.isEmpty())
    }

    @Test
    fun aGateThatVanishedWhileSigningKeepsItsReceiptId() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val decided = view.gate.copy(status = GateStatus.APPROVED, decidedAt = bench.clock.now)
        bench.repository.vanishOnLock = view.gate.id
        assertEquals("rcpt_${view.gate.id}", bench.receipts.ensureSigned(decided).receiptId)
    }

    @Test
    fun theKeyOfAMergeIsItsHeadBaseAndGateNode() {
        val subject = GateSubject(
            repo = "o/r",
            actionClass = "merge",
            command = "gh pr merge 6",
            scope = "session:s",
            pullRequest = GatePullRequest(6, SHA_A, "main"),
        )
        val node = GateNode("ship", "g-main", "Merge", listOf("rev-b"), true)
        assertEquals(
            """{"org":"org","repo":"o/r","action":"merge-pr","sha":"$SHA_A","base":"main","gate":"ship/g-main"}""",
            GateKeys.keyPreimage("org", subject, node),
        )
        assertEquals(
            """{"org":"org","repo":null,"action":"merge-pr","sha":"$SHA_A","base":"main","gate":null}""",
            GateKeys.keyPreimage("org", subject.copy(repo = null), null),
        )
    }

    @Test
    fun theKeyOfAPushIsItsRefsRemoteScopeAndForce() {
        val subject = GateSubject(
            repo = "o/r",
            realRepo = "o/fork",
            actionClass = "push-main",
            command = "git   push origin main",
            scope = "session:s",
            push = GatePush(
                "git@x:o/r",
                listOf(PushRef("refs/tags/v1", SHA_A), PushRef("refs/heads/main", SHA_A)),
                true,
            ),
        )
        assertEquals(
            """{"org":"org","repo":"o/r","realRepo":"o/fork","class":"push-main","action":"push",""" +
                """"remote":"git@x:o/r",""" +
                """"refs":["refs/heads/main=$SHA_A","refs/tags/v1=$SHA_A"],"scope":"session:s","force":true,""" +
                """"gate":null}""",
            GateKeys.keyPreimage("org", subject, null),
        )
        val deploy = subject.copy(push = null, actionClass = "deploy", command = " kubectl  apply -f x ")
        assertEquals(
            """{"org":"org","repo":"o/r","class":"deploy","command":"kubectl apply -f x","scope":"session:s",""" +
                """"gate":null}""",
            GateKeys.keyPreimage("org", deploy, null),
        )
        // A yes is the yes of one gate: a workflow that changes its gate or approvers asks a new one.
        val node = GateNode("ship", "g-main", "Push", listOf("rev-b"), true)
        assertTrue(GateKeys.keyPreimage("org", subject, node).endsWith(""""gate":"ship/g-main"}"""))
        assertTrue(GateKeys.keyPreimage("org", deploy, node).endsWith(""""gate":"ship/g-main"}"""))
    }

    @Test
    fun whatIsShownCoversTheSubjectAndTheEvidence() {
        val push = GateSubject(
            repo = null,
            actionClass = "push-main",
            command = "git push",
            scope = "session:s",
            push = GatePush("r", listOf(PushRef("refs/heads/main", SHA_A)), force = true),
            commits = listOf(SHA_A),
            diffDigest = "d",
        )
        assertEquals(
            """{"label":"Push","repo":null,"realRepo":null,"class":"push-main","command":"git push",""" +
                """"commits":["$SHA_A"],"diff":"d","pr":null,"push":"refs/heads/main=$SHA_A@r force",""" +
                """"evidence":["Repository: o/r"]}""",
            GateKeys.shownPreimage("Push", push, listOf(Evidence("Repository", "o/r"))),
        )
        val merge = push.copy(push = null, diffDigest = null, pullRequest = GatePullRequest(6, SHA_A, "main"))
        assertEquals(
            """{"label":"Merge","repo":null,"realRepo":null,"class":"push-main","command":"git push",""" +
                """"commits":["$SHA_A"],"diff":null,"pr":"#6@$SHA_A->main","push":null,"evidence":[]}""",
            GateKeys.shownPreimage("Merge", merge, emptyList()),
        )
        val plain = push.copy(push = GatePush("r", listOf(PushRef("refs/heads/main", SHA_A)), force = false))
        assertTrue(GateKeys.shownPreimage("Push", plain, emptyList()).contains(""""push":"refs/heads/main=$SHA_A@r""""))
    }

    @Test
    fun whoTheGateWaitsFor() {
        assertEquals(listOf("b"), SelfApproval.waitingFor(listOf("a"), listOf("a", "b"), SelfApprovalPolicy.NEVER))
        assertEquals(listOf("a"), SelfApproval.waitingFor(listOf("A"), listOf("a"), SelfApprovalPolicy.SOLE_APPROVER))
        assertEquals(emptyList(), SelfApproval.waitingFor(listOf("a"), listOf("a"), SelfApprovalPolicy.NEVER))
        assertEquals(
            emptyList(),
            SelfApproval.waitingFor(listOf("a", "b"), listOf("a", "b"), SelfApprovalPolicy.SOLE_APPROVER),
        )

        assertEquals(listOf("a", "b"), SelfApproval.withAskers(listOf("a"), "A", " ", "b"))
    }
}
