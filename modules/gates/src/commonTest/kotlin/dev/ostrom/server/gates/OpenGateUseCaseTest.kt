package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateWithoutApproverException
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RedirectedPushException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.usecases.GateUse
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.OTHER_SESSION
import dev.ostrom.server.gates.fakes.SESSION
import dev.ostrom.server.gates.fakes.SHA_A
import dev.ostrom.server.gates.fakes.SHA_B
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.device
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenGateUseCaseTest {
    @Test
    fun anEnforcedGateSendsOneRequestPerApproverAndWaitsForTheOthers() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest(node = bench.node("lyes", "rev-b", "REV-B")))

        assertEquals(GateMode.ENFORCED, view.gate.mode)
        assertEquals(GateStatus.PENDING, view.gate.status)
        assertEquals(
            listOf("lyes", "rev-b"),
            view.requests.map { it.approverId },
            "One request per approver, no duplicate",
        )
        assertEquals(listOf("rev-b"), view.waitingFor, "The asker is not waited for while someone else can say yes")
        assertEquals(1, bench.notifier.requested.size)
        assertEquals(listOf("lyes"), view.gate.askedBy)
        assertEquals(listOf(SESSION), view.gate.sessionIds)
    }

    @Test
    fun theSameActionAskedTwiceIsTheSameGate() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.pushRequest())
        val second = bench.open(bench.pushRequest(command = "git push origin main 2>&1 | tail -5"))

        assertEquals(first.gate.id, second.gate.id, "A piped push is the same push")
        assertEquals(1, bench.notifier.requested.size, "Nobody is disturbed twice")
        assertEquals(0, bench.repository.gateUpdates, "Nothing changed: the gate is not rewritten")
        assertEquals(1, bench.repository.gates.size)
    }

    @Test
    fun theHookAndTheGitHubCheckShareOneRequestForAPullRequest() = runTest {
        val bench = GateBench()
        val check = bench.open(bench.mergeRequest(viaCheck = true, asker = "pr-author"))
        val hook = bench.open(bench.mergeRequest())

        assertEquals(check.gate.id, hook.gate.id)
        assertEquals(setOf(AskedVia.CI, AskedVia.HOOK), hook.gate.askedVia)
        assertEquals(listOf("pr-author", "lyes"), hook.gate.askedBy)
        assertEquals(listOf(SESSION), hook.gate.sessionIds)
        assertEquals(listOf("rev-b"), hook.waitingFor)

        val again = bench.open(bench.mergeRequest(viaCheck = true, asker = "pr-author"))
        assertEquals(listOf(SESSION), again.gate.sessionIds, "A check without session keeps the sessions")
    }

    @Test
    fun aNewHeadCommitIsANewGate() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.mergeRequest(sha = SHA_A))
        val second = bench.open(bench.mergeRequest(sha = SHA_B))

        assertNotEquals(first.gate.id, second.gate.id)
    }

    @Test
    fun aYesForDevelopNeverCoversMain() = runTest {
        val bench = GateBench()
        val develop = bench.open(bench.mergeRequest(base = "develop", node = bench.node("rev-c")))
        bench.answer(develop, "rev-c")
        val main = bench.open(bench.mergeRequest(base = "main"))

        assertNotEquals(develop.gate.id, main.gate.id)
        assertEquals(GateStatus.PENDING, main.gate.status)
    }

    @Test
    fun aForcePushIsNotThePushThatWasApproved() = runTest {
        val bench = GateBench()
        val plain = bench.open(bench.pushRequest())
        bench.answer(plain, "rev-b")
        val forced = bench.open(bench.pushRequest(force = true, command = "git push --force origin main"))

        assertNotEquals(plain.gate.id, forced.gate.id)
        assertEquals(GateStatus.PENDING, forced.gate.status)
    }

    @Test
    fun anotherDiffAsksAnotherYes() = runTest {
        val bench = GateBench()
        val harmless = bench.open(bench.pushRequest(diff = "harmless"))
        bench.answer(harmless, "rev-b")
        val real = bench.open(bench.pushRequest(diff = "real"))

        assertNotEquals(harmless.gate.id, real.gate.id, "The yes is bound to the diff that was shown (audit A7)")
        assertEquals(GateStatus.PENDING, real.gate.status)
    }

    @Test
    fun anotherSessionAsksItsOwnYesForAPush() = runTest {
        val bench = GateBench()
        val mine = bench.open(bench.pushRequest())
        val theirs = bench.open(bench.pushRequest(session = OTHER_SESSION))

        assertNotEquals(mine.gate.id, theirs.gate.id, "A yes is never replayed in another session (audit A23)")
    }

    @Test
    fun aGatedPushThatRedirectsGitIsRefusedWithoutAskingAnyone() = runTest {
        val bench = GateBench()
        assertFailsWith<RedirectedPushException> { bench.open(bench.pushRequest(redirected = true)) }
        assertEquals(0, bench.notifier.requested.size)

        val shadow = bench.open(bench.pushRequest(redirected = true, node = bench.node("rev-b", enforced = false)))
        assertEquals(GateStatus.SHADOW, shadow.gate.status, "A gate that does not block records it")
        val ungated = bench.open(bench.pushRequest(redirected = true, node = null))
        assertEquals(GateStatus.UNGATED, ungated.gate.status)
        val merge = bench.open(bench.mergeRequest().copy(redirected = true))
        assertEquals(GateStatus.PENDING, merge.gate.status, "Only a push is refused for a redirect")
    }

    @Test
    fun anEnforcedGateWithoutApproverIsRefused() = runTest {
        val bench = GateBench()
        assertFailsWith<GateWithoutApproverException> { bench.open(bench.pushRequest(node = bench.node())) }
        assertTrue(bench.repository.gates.isEmpty())
    }

    @Test
    fun shadowAndUngatedActionsAreRecordedWithoutRequests() = runTest {
        val bench = GateBench()
        val shadow = bench.open(bench.pushRequest(node = bench.node("rev-b", enforced = false)))
        val ungated = bench.open(bench.pushRequest(node = null, sha = SHA_B))

        assertEquals(GateMode.SHADOW, shadow.gate.mode)
        assertEquals(GateMode.UNGATED, ungated.gate.mode)
        assertTrue(shadow.requests.isEmpty() && ungated.requests.isEmpty())
        assertTrue(shadow.waitingFor.isEmpty())
        assertEquals(0, bench.notifier.requested.size)
    }

    @Test
    fun aNoHoldsForTheScope() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.pushRequest())
        bench.answer(first, "rev-b", Answer.NO)
        val again = bench.open(bench.pushRequest())

        assertEquals(first.gate.id, again.gate.id)
        assertEquals(GateStatus.REJECTED, again.gate.status)
        assertEquals(1, bench.notifier.requested.size, "Asking again does not disturb the approver again")
    }

    @Test
    fun aUsedYesDoesNotCoverTheSameActionAgain() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.pushRequest())
        bench.answer(first, "rev-b")
        bench.use(agent(), first.gate.id, GateUse.RESERVE)
        bench.use(agent(), first.gate.id, GateUse.CONSUME)
        val again = bench.open(bench.pushRequest())

        assertNotEquals(first.gate.id, again.gate.id)
        assertEquals(GateStatus.PENDING, again.gate.status)
    }

    @Test
    fun aClosedSessionTakesNoGate() = runTest {
        val bench = GateBench()
        bench.sessions.closed += SESSION
        assertFailsWith<OstromException> { bench.open(bench.pushRequest()) }
        assertTrue(bench.repository.gates.isEmpty())
    }

    @Test
    fun anAgentTokenOnlyAsksForItsOwnSession() = runTest {
        val bench = GateBench()
        val other = assertFailsWith<OstromException> {
            bench.open(bench.pushRequest().copy(actor = agent(OTHER_SESSION)))
        }
        assertEquals("other launch", other.message, "The sessions module refuses a session of another launch")
        assertFailsWith<NotYourGateException> { bench.open(bench.pushRequest().copy(sessionId = null)) }
        val unscoped = agent().copy(launchId = null)
        assertFailsWith<NotYourGateException> { bench.open(bench.pushRequest().copy(actor = unscoped)) }
        for (id in listOf("", " ", "s".repeat(129))) {
            assertFailsWith<InvalidGateRequestException> {
                bench.open(bench.pushRequest().copy(sessionId = id, actor = device()))
            }

        }
        val fromDevice = bench.open(bench.pushRequest().copy(actor = device()))
        assertEquals(GateStatus.PENDING, fromDevice.gate.status, "A device token of the developer may ask")
    }

    @Test
    fun outOfBoundFieldsAreRefused() = runTest {
        val bench = GateBench()
        val push = bench.pushRequest()
        val merge = bench.mergeRequest()
        val pushOf = { p: GatePush -> push.copy(subject = push.subject.copy(push = p)) }
        val good = push.subject.push!!
        val invalid = listOf(
            push.copy(subject = push.subject.copy(command = "x".repeat(8_001))),
            push.copy(evidence = List(31) { Evidence("l", "v") }),
            push.copy(evidence = listOf(Evidence("l".repeat(201), "v"))),
            push.copy(evidence = listOf(Evidence("l", "v".repeat(8_001)))),
            push.copy(label = "l".repeat(201)),
            push.copy(subject = push.subject.copy(commits = listOf("HEAD"))),
            push.copy(subject = push.subject.copy(commits = List(251) { SHA_A })),
            merge.copy(subject = merge.subject.copy(pullRequest = GatePullRequest(6, "abc", "main"))),
            merge.copy(subject = merge.subject.copy(pullRequest = GatePullRequest(6, SHA_A, "b".repeat(201)))),
            pushOf(good.copy(refs = emptyList())),
            pushOf(good.copy(refs = List(51) { PushRef("refs/heads/x$it", SHA_A) })),
            pushOf(good.copy(remote = "r".repeat(201))),
            pushOf(good.copy(refs = listOf(PushRef(" ", SHA_A)))),
            pushOf(good.copy(refs = listOf(PushRef("r".repeat(201), SHA_A)))),
            pushOf(good.copy(refs = listOf(PushRef("refs/heads/main", "ABCDEF")))),
            pushOf(good.copy(refs = listOf(PushRef("refs/heads/main", "A".repeat(40))))),
        )
        for (request in invalid) assertFailsWith<InvalidGateRequestException> { bench.open(request) }
        assertTrue(bench.repository.gates.isEmpty())

        val sha256 = "c".repeat(64)
        val long = bench.open(pushOf(good.copy(refs = listOf(PushRef("refs/heads/main", sha256)))))
        assertEquals(GateStatus.PENDING, long.gate.status, "A SHA-256 commit id is accepted")
    }

    @Test
    fun aFailedNotificationNeverLosesTheRequest() = runTest {
        val bench = GateBench()
        bench.notifier.failure = IllegalStateException("slack down")
        val view = bench.open(bench.pushRequest())

        assertEquals(GateStatus.PENDING, view.gate.status)
        assertEquals(1, bench.repository.gates.size)
        assertEquals(LogLevel.WARN to "gate notification failed", bench.logger.lines.single())
    }

    @Test
    fun invokeWithCancellationPropagatesException() = runTest {
        val bench = GateBench()
        bench.notifier.failure = CancellationException("caller gone")
        assertFailsWith<CancellationException> { bench.open(bench.pushRequest()) }
    }

    @Test
    fun aJoinedGateKeepsItsRequests() = runTest {
        val bench = GateBench()
        val first = bench.open(bench.mergeRequest(viaCheck = true, asker = "pr-author"))
        val joined = bench.open(bench.mergeRequest(asker = "lyes"))

        assertEquals(first.requests.map { it.id }, joined.requests.map { it.id })
        assertTrue(joined.requests.all { it.status == RequestStatus.PENDING })
        assertEquals(1, bench.repository.gateUpdates)
    }
}
