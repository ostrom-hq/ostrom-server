// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateAlreadyDecidedException
import dev.ostrom.server.gates.domain.models.GateChangedException
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.NotTheApproverException
import dev.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.models.SelfApprovalRefusedException
import dev.ostrom.server.gates.domain.models.StepUp
import dev.ostrom.server.gates.domain.models.StepUpRequiredException
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.ORG
import dev.ostrom.server.gates.fakes.OTHER_ORG
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.ciService
import dev.ostrom.server.gates.fakes.device
import dev.ostrom.server.gates.fakes.user
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AnswerGateUseCaseTest {
    @Test
    fun s254AYesNeedsASignInOfAtMostFiveMinutesANoDoesNot() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val request = view.requests.first { it.approverId == "rev-b" }.id
        suspend fun yes(signedIn: Instant?) = bench.answer(
            user("rev-b").copy(authenticatedAt = signedIn),
            request,
            Answer.YES,
            view.gate.shownDigest,
            AnswerChannel.CONSOLE,
        )
        for (stale in listOf(null, bench.clock.now - StepUp.WITHIN - 1.seconds)) {
            val refused = assertFailsWith<StepUpRequiredException> { yes(stale) }
            assertEquals(ErrorKind.FORBIDDEN, refused.kind)
        }
        assertEquals(GateStatus.PENDING, bench.repository.gates.single().status, "nothing written")
        assertEquals(GateStatus.APPROVED, yes(bench.clock.now - StepUp.WITHIN).gate.status)

        val other = bench.open(bench.pushRequest(sha = "b".repeat(40)))
        val no = other.requests.first { it.approverId == "rev-b" }.id
        val refused = bench.answer(user("rev-b"), no, Answer.NO, other.gate.shownDigest, AnswerChannel.CONSOLE)
        assertEquals(GateStatus.REJECTED, refused.gate.status, "saying no never needs a fresh sign-in")
    }

    @Test
    fun s215APersonAnswersNeverACredentialWithoutPerson() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val keyless = GateActor("rev-b", ORG, ActorKind.USER)
        assertFailsWith<OnlyPeopleAnswerException> { bench.answer(view, "rev-b", actor = keyless) }
        assertEquals(GateStatus.PENDING, bench.repository.gates.getValue(view.gate.id).status)
    }

    @Test
    fun theFirstYesOpensTheGateAndClosesTheOtherRequests() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(node = bench.node("rev-b", "rev-c", "lyes")))
        val answered = bench.answer(view, "rev-c")

        assertEquals(GateStatus.APPROVED, answered.gate.status)
        assertEquals("rev-c", answered.gate.decidedBy)
        assertFalse(answered.gate.selfApproved)
        assertEquals(
            listOf(RequestStatus.CLOSED, RequestStatus.APPROVED, RequestStatus.CLOSED),
            answered.requests.map { it.status },
        )
        assertEquals(AnswerChannel.MOBILE, answered.requests[1].channel)
        assertEquals("rcpt_${view.gate.id}", answered.gate.receiptId)
        assertEquals("rcpt_${view.gate.id}", bench.repository.gates.getValue(view.gate.id).receiptId)
        assertEquals(1, bench.notifier.decided.size)
        assertTrue(answered.waitingFor.isEmpty())

        assertFailsWith<GateAlreadyDecidedException>("The closed request cannot open it again") {
            bench.answer(view, "rev-b")
        }
    }

    @Test
    fun aNoRefusesTheGateAndClosesTheOthers() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(node = bench.node("rev-b", "rev-c")))
        val answered = bench.answer(view, "rev-b", Answer.NO)

        assertEquals(GateStatus.REJECTED, answered.gate.status)
        assertEquals(listOf(RequestStatus.REJECTED, RequestStatus.CLOSED), answered.requests.map { it.status })
        assertEquals("rcpt_${view.gate.id}", answered.gate.receiptId, "A no is signed too")
    }

    @Test
    fun twoApproversAtTheSameInstantOpenTheGateOnce() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(node = bench.node("rev-b", "rev-c")))
        val results = listOf("rev-b", "rev-c").map { approver ->
            async {
                try {
                    bench.answer(view, approver).gate.decidedBy
                } catch (_: GateAlreadyDecidedException) {
                    null
                }
            }
        }.awaitAll()

        assertEquals(1, results.count { it != null }, "Exactly one yes wins")
        assertEquals(1, bench.signer.signed.size)
    }

    @Test
    fun onlyAPersonAnswers() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        for (actor in listOf(device("rev-b"), agent(), ciService())) {
            assertFailsWith<OnlyPeopleAnswerException> { bench.answer(view, "rev-b", actor = actor) }
        }
        assertEquals(GateStatus.PENDING, bench.repository.gates.getValue(view.gate.id).status)
    }

    @Test
    fun anAdminNeverAnswersInAnApproversPlace() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        assertFailsWith<NotTheApproverException> { bench.answer(view, "rev-b", actor = user("admin")) }
        val sameNameOtherCase = bench.answer(view, "rev-b", actor = user("Rev-B"))
        assertEquals(GateStatus.APPROVED, sameNameOtherCase.gate.status, "Logins compare without case, as GitHub's")
    }

    @Test
    fun aRequestOfAnotherOrganizationDoesNotExist() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        assertFailsWith<GateNotFoundException> { bench.answer(view, "rev-b", actor = user("rev-b", OTHER_ORG)) }
        assertFailsWith<GateNotFoundException> {
            bench.answer(user("rev-b"), Uuid.random(), Answer.YES, view.gate.shownDigest, AnswerChannel.CONSOLE)
        }
        bench.repository.vanishOnLock = view.gate.id
        assertFailsWith<GateNotFoundException> { bench.answer(view, "rev-b") }
    }

    @Test
    fun aDecidedGateOrRequestTakesNoAnswer() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val request = view.requests.last()
        bench.repository.requests[request.id] = request.copy(status = RequestStatus.CLOSED)
        assertFailsWith<GateAlreadyDecidedException> { bench.answer(view, "rev-b") }
    }

    @Test
    fun aYesOnSomethingElseThanWhatWasShownIsRefused() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        assertFailsWith<GateChangedException> {
            bench.answer(user("rev-b"), view.requests.last().id, Answer.YES, "something else", AnswerChannel.SLACK)
        }
        assertEquals(GateStatus.PENDING, bench.repository.gates.getValue(view.gate.id).status)
    }

    @Test
    fun theAskerCannotApproveWhileAnotherApproverExists() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(asker = "lyes", node = bench.node("lyes", "rev-b")))
        val refused = assertFailsWith<SelfApprovalRefusedException> { bench.answer(view, "lyes") }

        assertEquals(listOf("rev-b"), refused.others)
        assertEquals("You asked for this action and cannot approve it: rev-b must say yes.", refused.message)
        val stored = bench.repository.gates.getValue(view.gate.id)
        assertEquals(GateStatus.PENDING, stored.status, "The request stays pending")
        assertTrue(bench.repository.requests.values.all { it.status == RequestStatus.PENDING })
    }

    @Test
    fun aPullRequestAuthorWhoJoinedCannotApproveEither() = runTest {
        val bench = GateBench()
        bench.open(bench.mergeRequest(viaCheck = true, asker = "rev-b"))
        val view = bench.open(bench.mergeRequest(asker = "lyes"))
        val refused = assertFailsWith<SelfApprovalRefusedException> { bench.answer(view, "rev-b") }
        assertEquals(emptyList(), refused.others, "Both approvers asked: nobody else can say yes")
        assertTrue(view.waitingFor.isEmpty(), "Nobody can open it: an approver must be added")
    }

    @Test
    fun theOnlyApproverWhoAskedIsMarkedSelfApproved() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(asker = "lyes", node = bench.node("lyes")))
        assertEquals(listOf("lyes"), view.waitingFor)
        val answered = bench.answer(view, "lyes")

        assertEquals(GateStatus.APPROVED, answered.gate.status)
        assertTrue(answered.gate.selfApproved)
        assertTrue(bench.signer.signed.single().selfApproved, "The receipt carries the mark")
    }

    @Test
    fun anOrganizationThatNeverAllowsSelfApprovalWaitsForSomeoneElse() = runTest {
        val bench = GateBench()
        bench.policies.policy = SelfApprovalPolicy.NEVER
        val view = bench.open(bench.pushRequest(asker = "lyes", node = bench.node("lyes")))
        assertTrue(view.waitingFor.isEmpty())

        val refused = assertFailsWith<SelfApprovalRefusedException> { bench.answer(view, "lyes") }
        assertEquals(
            "You asked for this action and cannot approve it, and nobody else on this gate can: " +
                "add another approver to the gate.",
            refused.message,
        )
        val withdrawn = bench.answer(view, "lyes", Answer.NO)
        assertEquals(GateStatus.REJECTED, withdrawn.gate.status, "A no from the asker always passes")
    }

    @Test
    fun aFailedSignatureKeepsTheDecisionAndTheNextReadSignsIt() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        bench.signer.failure = IllegalStateException("key unavailable")
        val answered = bench.answer(view, "rev-b")

        assertEquals(GateStatus.APPROVED, answered.gate.status)
        assertNull(answered.gate.receiptId)
        assertTrue(bench.logger.lines.contains(LogLevel.ERROR to "gate receipt signing failed"))

        bench.signer.failure = null
        val read = bench.get(user("rev-b"), view.gate.id)
        assertEquals("rcpt_${view.gate.id}", read.gate.receiptId)
    }

    @Test
    fun invokeWithCancellationPropagatesException() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        bench.notifier.failure = CancellationException("caller gone")
        assertFailsWith<CancellationException> { bench.answer(view, "rev-b") }

        bench.notifier.failure = null
        val other = bench.open(bench.pushRequest(sha = dev.ostrom.server.gates.fakes.SHA_B))
        bench.signer.failure = CancellationException("caller gone")
        assertFailsWith<CancellationException> { bench.answer(other, "rev-b") }
    }

    @Test
    fun theInboxListsOnlyTheCallersPendingRequests() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.pushRequest())
        val second = bench.open(bench.pushRequest(sha = dev.ostrom.server.gates.fakes.SHA_B))
        bench.answer(first, "rev-b")
        val pending = RequestStatus.PENDING

        assertEquals(listOf(second.gate), bench.approvals(user("rev-b"), true, pending, 500).map { it.gate })
        assertEquals(1, bench.approvals(user("rev-b"), true, pending, 0).size, "The limit is at least one")
        assertEquals(1, bench.approvals(user("lyes"), true, pending, 10).size, "The closed request is not pending")
        assertEquals(4, bench.approvals(user("lyes"), false, null, 10).size, "Without `mine`: the organization's")
        assertEquals(
            listOf(RequestStatus.CLOSED),
            bench.approvals(user("lyes"), true, RequestStatus.CLOSED, 10).map { it.request.status },
        )
        assertTrue(bench.approvals(user("rev-b").copy(orgId = OTHER_ORG), false, null, 10).isEmpty())
        assertFailsWith<OnlyPeopleAnswerException> { bench.approvals(device(), true, pending, 10) }
    }

    @Test
    fun anApprovalIsReadWithItsGate() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val request = view.requests.last()

        assertEquals(view.gate, bench.approval(user("rev-b"), request.id).gate)
        assertEquals(request, bench.approval(user("lyes"), request.id).request)
        assertFailsWith<GateNotFoundException> { bench.approval(user("rev-b").copy(orgId = OTHER_ORG), request.id) }
        assertFailsWith<GateNotFoundException> { bench.approval(user("rev-b"), Uuid.random()) }
        assertFailsWith<OnlyPeopleAnswerException> { bench.approval(agent(), request.id) }
    }
}
