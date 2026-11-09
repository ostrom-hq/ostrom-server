// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.ApprovalRights
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.CallerRights
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNotFoundException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.models.OnlyPeopleAnswerException
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.gates.domain.models.Viewer
import run.ostrom.server.gates.domain.models.ViewerChannel
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCaseImpl
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.fakes.ORG
import run.ostrom.server.gates.fakes.OTHER_ORG
import run.ostrom.server.gates.fakes.agent
import run.ostrom.server.gates.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The yes queue and the approval view: what each reader may do, by the rules of the answer route. */
class ApprovalQueueTest {
    private val console = Viewer(ViewerChannel.CONSOLE, answers = true)
    private val app = Viewer(ViewerChannel.APP, answers = true)

    private suspend fun GateBench.opened(vararg approvers: String, sha: String = "a".repeat(40)): GateView =
        open(pushRequest(sha = sha, node = node(*approvers)))

    private fun GateView.of(approver: String): ApprovalView =
        ApprovalView(requests.first { it.approverId == approver }, gate)

    @Test
    fun theApproverWhoDidNotAskMaySayYesFromTheConsoleOrTheApp() = runTest {
        val bench = GateBench()
        val view = bench.opened("lyes", "rev-b").of("rev-b")
        val approvers = listOf("lyes", "rev-b")
        val expected = CallerRights("rev-b", self = false, canApprove = true)
        assertEquals(expected, ApprovalRights.of("rev-b", console, view, approvers, SelfApprovalPolicy.NEVER))
        val upper = ApprovalRights.of("Rev-B", app, view, approvers, SelfApprovalPolicy.NEVER)
        assertEquals(expected.copy(login = "Rev-B"), upper, "an identity is the same whatever its case")
    }

    @Test
    fun aProgramOrAReadOnlyCredentialNeverSaysYes() = runTest {
        val bench = GateBench()
        val view = bench.opened("lyes", "rev-b").of("rev-b")
        val approvers = listOf("lyes", "rev-b")
        val program = Viewer(ViewerChannel.CLI, answers = true)
        val cli = ApprovalRights.of("rev-b", program, view, approvers, SelfApprovalPolicy.NEVER)
        assertEquals(CallerRights("rev-b", false, false, "A yes is given in the browser or in the app."), cli)
        val reader = ApprovalRights.of(
            "rev-b",
            Viewer(ViewerChannel.CONSOLE, answers = false),
            view,
            approvers,
            SelfApprovalPolicy.NEVER,
        )
        val readOnly = "This credential reads approvals but does not answer them."
        assertEquals(CallerRights("rev-b", false, false, readOnly), reader)
    }

    @Test
    fun someoneElsesRequestOrAnAnsweredOneIsNotTheirsToApprove() = runTest {
        val bench = GateBench()
        val view = bench.opened("lyes", "rev-b").of("rev-b")
        val approvers = listOf("lyes", "rev-b")
        val other = ApprovalRights.of("rev-c", console, view, approvers, SelfApprovalPolicy.NEVER)
        assertEquals(
            CallerRights("rev-c", false, false, "Only the approver this request was sent to can answer it."),
            other,
        )
        val answered = "This gate was already answered."
        val decided = view.copy(gate = view.gate.copy(status = GateStatus.APPROVED))
        assertEquals(
            CallerRights("rev-b", false, false, answered),
            ApprovalRights.of("rev-b", console, decided, approvers, SelfApprovalPolicy.NEVER),
        )
        val closed = view.copy(request = view.request.copy(status = RequestStatus.CLOSED))
        assertEquals(
            CallerRights("rev-b", false, false, answered),
            ApprovalRights.of("rev-b", console, closed, approvers, SelfApprovalPolicy.NEVER),
            "a request closed while its gate still waits",
        )
    }

    @Test
    fun theAskerApprovesOnlyAsTheSoleApproverWhenTheOrganizationAllowsIt() = runTest {
        val bench = GateBench()
        val shared = bench.opened("lyes", "rev-b").of("lyes")
        assertEquals(
            CallerRights("lyes", true, false, "You asked for this action and cannot approve it: rev-b must say yes."),
            ApprovalRights.of("lyes", console, shared, listOf("lyes", "rev-b"), SelfApprovalPolicy.SOLE_APPROVER),
        )
        val alone = bench.opened("lyes", sha = "b".repeat(40)).of("lyes")
        assertEquals(
            CallerRights("lyes", self = true, canApprove = true, selfApproval = true),
            ApprovalRights.of("lyes", console, alone, listOf("lyes"), SelfApprovalPolicy.SOLE_APPROVER),
        )
        assertEquals(
            CallerRights(
                "lyes",
                true,
                false,
                "You asked for this action and cannot approve it, and nobody else on this gate can: " +
                    "add another approver to the gate.",
            ),
            ApprovalRights.of("lyes", console, alone, listOf("lyes"), SelfApprovalPolicy.NEVER),
        )
    }

    @Test
    fun theViewReadsTheApprovalWithTheApproversOfItsGate() = runTest {
        val bench = GateBench()
        val opened = bench.opened("lyes", "rev-b")
        val id = opened.of("rev-b").request.id
        val screen = bench.view(user("rev-b"), console, id)
        assertEquals(opened.of("rev-b"), screen.view)
        assertEquals(CallerRights("rev-b", self = false, canApprove = true), screen.rights)
        assertEquals(listOf(listOf(opened.gate.id)), bench.repository.approverReads, "one read of its approvers")
        val asker = bench.view(user("lyes"), console, opened.of("lyes").request.id).rights
        assertEquals("You asked for this action and cannot approve it: rev-b must say yes.", asker.reason)
    }

    // PARCOURS 3.3: a session links each of its gates by the gate's id, and the view reads that id too.
    @Test
    fun theViewOfAGateIsTheReadersOwnRequestElseItsFirst() = runTest {
        val bench = GateBench()
        val opened = bench.opened("lyes", "rev-b")
        val gateId = opened.gate.id
        assertEquals(opened.of("rev-b"), bench.view(user("Rev-B"), console, gateId).view, "their own, case ignored")
        val first = bench.repository.requestsOf(ORG, gateId).first()
        assertEquals(ApprovalView(first, opened.gate), bench.view(user("dev"), console, gateId).view)
        assertFailsWith<GateNotFoundException> { bench.view(user("rev-b", OTHER_ORG), console, gateId) }
    }

    @Test
    fun anApprovalIsReadByItsGatesIdAsTheReadersRequest() = runTest {
        val bench = GateBench()
        val opened = bench.opened("lyes", "rev-b")
        val read = GetApprovalUseCaseImpl(bench.repository)
        assertEquals(opened.of("rev-b"), read(user("rev-b"), opened.gate.id))
        val noPerson = GateActor("rev-b", ORG, ActorKind.USER)
        assertEquals(opened.of("rev-b"), read(noPerson, opened.gate.id), "the credential's id when it names no person")
        assertFailsWith<GateNotFoundException> { read(user("rev-b", OTHER_ORG), opened.gate.id) }
    }

    @Test
    fun theViewIsReadByAPersonOfTheOrganizationOnly() = runTest {
        val bench = GateBench()
        val id = bench.opened("lyes", "rev-b").of("rev-b").request.id
        assertFailsWith<OnlyPeopleAnswerException> { bench.view(agent(), console, id) }
        val nobody = GateActor("rev-b", ORG, ActorKind.USER)
        assertFailsWith<OnlyPeopleAnswerException>("a person's credential always names its person") {
            bench.view(nobody, console, id)
        }
        assertFailsWith<GateNotFoundException> { bench.view(user("rev-b", OTHER_ORG), console, id) }
        assertFailsWith<GateNotFoundException> { bench.view(user("rev-b"), console, Uuid.random()) }
    }

    @Test
    fun theQueueHoldsTheCallersPendingRequestsAndTheirLastAnswers() = runTest {
        val bench = GateBench()
        bench.policies.policy = SelfApprovalPolicy.NEVER
        val approved = bench.opened("lyes", "rev-b", "rev-c", sha = "1".repeat(40))
        bench.answer(approved, "rev-b", Answer.YES)
        bench.clock.now += 1.minutes
        val rejected = bench.opened("lyes", "rev-b", "rev-c", sha = "2".repeat(40))
        bench.answer(rejected, "rev-b", Answer.NO)
        bench.clock.now += 1.minutes
        val closed = bench.opened("lyes", "rev-b", "rev-c", sha = "3".repeat(40))
        bench.answer(closed, "rev-c", Answer.YES)
        bench.clock.now += 1.minutes
        val waiting = bench.opened("lyes", "rev-b", "rev-c", sha = "4".repeat(40))
        bench.repository.approverReads.clear()

        val queue = bench.queue(user("rev-b"), app)
        assertEquals("rev-b", queue.login)
        assertEquals(app, queue.viewer)
        assertEquals(listOf(waiting.of("rev-b").request.id), queue.pending.map { it.view.request.id })
        assertEquals(CallerRights("rev-b", self = false, canApprove = true), queue.pending.single().rights)
        assertEquals(
            listOf(closed.gate.id, rejected.gate.id, approved.gate.id),
            queue.recent.map { it.view.gate.id },
            "every answered status, newest first",
        )
        assertEquals(
            listOf(RequestStatus.CLOSED, RequestStatus.REJECTED, RequestStatus.APPROVED),
            queue.recent.map { it.view.request.status },
        )
        assertTrue(queue.recent.all { it.rights.reason == "This gate was already answered." })
        assertEquals(1, bench.repository.approverReads.size, "one read of the approvers for the whole queue")
        assertEquals(
            setOf(waiting.gate.id, closed.gate.id, rejected.gate.id, approved.gate.id),
            bench.repository.approverReads.single().toSet(),
        )

        val asker = bench.queue(user("lyes"), console).pending.single().rights
        assertEquals(true, asker.self)
        assertEquals("You asked for this action and cannot approve it: rev-b or rev-c must say yes.", asker.reason)
    }

    @Test
    fun theQueueShowsTheEightLastAnswers() = runTest {
        val bench = GateBench()
        val gates = (0 until 10).map { index ->
            bench.clock.now += 1.minutes
            bench.opened("lyes", "rev-b", sha = index.toString().repeat(40)).also { bench.answer(it, "rev-b") }
        }
        val recent = bench.queue(user("rev-b"), console).recent
        assertEquals(gates.reversed().take(8).map { it.gate.id }, recent.map { it.view.gate.id })
    }

    @Test
    fun theQueueIsAPersonsAndEmptyInAnotherOrganization() = runTest {
        val bench = GateBench()
        bench.opened("lyes", "rev-b")
        assertFailsWith<OnlyPeopleAnswerException> { bench.queue(agent(), console) }
        assertFailsWith<OnlyPeopleAnswerException> { bench.queue(GateActor("rev-b", ORG, ActorKind.USER), console) }
        val elsewhere = bench.queue(user("rev-b", OTHER_ORG), console)
        assertTrue(elsewhere.pending.isEmpty() && elsewhere.recent.isEmpty())
    }
}
