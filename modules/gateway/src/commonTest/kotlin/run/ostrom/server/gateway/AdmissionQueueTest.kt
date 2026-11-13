// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.AllowAllSpend
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCase
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCaseImpl
import run.ostrom.server.gateway.domain.GetInferenceQueueUseCaseImpl
import run.ostrom.server.gateway.domain.InferencePlan
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.InferenceRefusal
import run.ostrom.server.gateway.domain.QueueCandidate
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.gateway.domain.QueueRefusedException
import run.ostrom.server.gateway.domain.QueueTurns
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.UpstreamRequest
import run.ostrom.server.gateway.domain.Workload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A queue that answers what the test scripts and records what it was asked. */
class ScriptedQueue(vararg entries: AdmissionEntry) : AdmissionQueueRepository {
    private val script = ArrayDeque(entries.toList())
    val calls = mutableListOf<String>()
    var refusal: RefusalKind? = null
    var failure: Exception? = null

    override suspend fun enter(request: AdmissionRequest, first: Boolean): AdmissionEntry {
        calls += if (first) "enter" else "poll"
        refusal?.let { throw QueueRefusedException(it) }
        return script.removeFirstOrNull() ?: AdmissionEntry.Waiting(blocked = false)
    }

    override suspend fun requeue(orgId: Uuid, id: Uuid) {
        calls += "requeue"
    }

    override suspend fun renew(orgId: Uuid, id: Uuid) {
        calls += "renew"
        failure?.let { throw it }
    }

    override suspend fun release(orgId: Uuid, id: Uuid) {
        calls += "release"
        failure?.let { throw it }
    }

    override suspend fun counts(orgId: Uuid, memberId: Uuid) = QueueCounts(3, 1)
}

/** The admission queue's order and its waits, on a scripted queue. */
class AdmissionQueueTest {
    private val clock = TestClock()
    private val pauses = mutableListOf<Duration>()
    private val request =
        AdmissionRequest(Uuid.random(), Gw.orgA, Gw.memberA.memberId, "claude", Workload.INTERACTIVE, 2)
    private val upstreamRequest = UpstreamRequest("https://x.test", emptyList(), "{}")
    private val ready = InferencePlan.Ready(upstreamRequest, InferenceProvider.ANTHROPIC, null, "m", false)
    private val busy = InferencePlan.Busy(InferenceRefusal(RefusalKind.NO_CAPACITY, "busy"), "claude-opus-5")

    private fun await(queue: AdmissionQueueRepository) = AwaitAdmissionUseCaseImpl(
        queue,
        clock,
        pause = {
            pauses += it
            clock.advance(it)
        },
        random = Random(1),
    )

    private fun candidate(
        member: Uuid,
        workload: Workload = Workload.INTERACTIVE,
        waited: Duration = 0.seconds,
        turn: Long = 0,
    ) = QueueCandidate(Uuid.random(), member, workload, waited, turn)

    @Test
    fun theWorkloadHeaderNamesInteractiveByDefaultAndNothingElse() {
        assertEquals(Workload.INTERACTIVE, Workload.named(null))
        assertEquals(Workload.BACKGROUND, Workload.named("background"))
        assertEquals(Workload.INTERACTIVE, Workload.named("interactive"))
        assertNull(Workload.named("background,background"), "Twice is refused")
        assertNull(Workload.named("BACKGROUND"))
    }

    /** [QueueTurns.decide], with no other member limited. */
    private fun decide(
        call: QueueCandidate,
        others: List<QueueCandidate>,
        running: Int,
        limit: Int?,
        limited: Set<String> = emptySet(),
    ) = QueueTurns.decide(call, others, running, limit, limited)

    @Test
    fun withoutALimitAmongTheWaitingCallsEveryCallRunsAsItAsks() {
        // TeamCode #87: taking turns let one waiting call through per poll and timed the others out.
        val ada = Uuid.random()
        val bob = Uuid.random()
        val late = candidate(ada, Workload.BACKGROUND)
        val ahead = listOf(candidate(bob, waited = 40.seconds), candidate(ada, waited = 20.seconds))
        assertEquals(AdmissionEntry.Admitted, decide(late, ahead, running = 5, limit = null))
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            decide(late, ahead, running = 5, limit = null, limited = setOf(bob.toString())),
            "One limited member among the waiting calls brings the turns back",
        )
    }

    @Test
    fun behindALimitInteractiveCallsGoFirstMembersTakeTurnsAndOneMembersCallsKeepTheirOrder() {
        val ada = Uuid.random()
        val bob = Uuid.random()
        val call = candidate(ada, Workload.BACKGROUND, waited = 10.seconds)
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            decide(call, listOf(candidate(bob)), running = 0, limit = 1),
            "An interactive call goes before a background one",
        )
        val promoted = candidate(ada, Workload.BACKGROUND, waited = QueueTurns.PROMOTION)
        assertEquals(
            AdmissionEntry.Admitted,
            decide(promoted, listOf(candidate(bob, waited = 1.seconds)), running = 0, limit = 1),
            "A background call that waited 30 s joins the interactive ones, and waited longer",
        )
        val promotedBob = candidate(bob, Workload.BACKGROUND, waited = 40.seconds)
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            decide(candidate(ada, waited = 1.seconds), listOf(promotedBob), running = 0, limit = 1),
            "Promoted, it goes first by its wait",
        )
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            decide(candidate(ada, turn = 5), listOf(candidate(bob, turn = 4)), running = 0, limit = 1),
            "The member served longest ago goes first",
        )
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            decide(candidate(ada, waited = 1.seconds), listOf(candidate(ada, waited = 2.seconds)), 0, 1),
            "A member's calls go in arrival order",
        )
        val first = candidate(ada).copy(id = Uuid.parse("00000000-0000-4000-8000-000000000001"))
        val second = first.copy(id = Uuid.parse("ffffffff-ffff-4fff-bfff-ffffffffffff"))
        assertEquals(AdmissionEntry.Admitted, decide(first, listOf(second), 0, 1), "Ties by id")
        assertEquals(AdmissionEntry.Admitted, decide(candidate(ada), emptyList(), running = 1, limit = 2))
        assertEquals(
            AdmissionEntry.Waiting(blocked = true),
            decide(candidate(ada), emptyList(), running = 2, limit = 2),
            "A member at their limit waits for one of their calls to end",
        )
    }

    @Test
    fun aCallThatMayStartAtOncePlansAtOnce() = runTest {
        val queue = ScriptedQueue(AdmissionEntry.Admitted)
        assertSame(ready, await(queue)(request, running = false) { ready })
        assertEquals(listOf("enter"), queue.calls)
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun aCallWaitsForItsTurnThenAgainWhileTheAccountsAreBusy() = runTest {
        val queue = ScriptedQueue(
            AdmissionEntry.Waiting(blocked = true),
            AdmissionEntry.Admitted,
            AdmissionEntry.Waiting(blocked = false),
            AdmissionEntry.Admitted,
        )
        val plans = ArrayDeque(listOf(busy, ready))
        assertSame(ready, await(queue)(request, running = false) { plans.removeFirst() })
        assertEquals(listOf("enter", "poll", "requeue", "poll", "poll"), queue.calls, "Busy puts it back at its place")
        assertEquals(List(3) { 500.milliseconds }, pauses)
    }

    @Test
    fun aLaterTryGoesBackToWaitFirst() = runTest {
        val queue = ScriptedQueue(AdmissionEntry.Admitted)
        assertSame(ready, await(queue)(request, running = true) { ready })
        assertEquals(listOf("requeue", "poll"), queue.calls)
    }

    @Test
    fun aFullQueueOrALostPlaceIsRefusedAtOnce() = runTest {
        val queue = ScriptedQueue().apply { refusal = RefusalKind.QUEUE_FULL }
        val full = assertIs<InferencePlan.Refused>(await(queue)(request, running = false) { ready })
        assertEquals(RefusalKind.QUEUE_FULL, full.refusal.kind)
        assertEquals(clock.now() + 15.seconds, full.refusal.retryAt)
        assertTrue("full" in full.refusal.message)
        queue.refusal = RefusalKind.QUEUE_EXPIRED
        val expired = assertIs<InferencePlan.Refused>(await(queue)(request, running = false) { ready })
        assertEquals(RefusalKind.QUEUE_EXPIRED, expired.refusal.kind)
        assertTrue("expired" in expired.refusal.message)
        assertEquals(503, RefusalKind.QUEUE_FULL.status)
        assertEquals("queue_full", RefusalKind.QUEUE_FULL.code)
        assertEquals("api_error", RefusalKind.QUEUE_TIMEOUT.anthropicType, "Never overloaded_error")
        assertTrue("QUEUE_FULL" in QueueRefusedException(RefusalKind.QUEUE_FULL).message.orEmpty())
    }

    @Test
    fun aCallGivesUpAfterNinetySecondsAndSaysWhatItWaitedFor() = runTest {
        val busyAccounts = assertIs<InferencePlan.Refused>(await(ScriptedQueue())(request, running = false) { ready })
        assertEquals(RefusalKind.QUEUE_TIMEOUT, busyAccounts.refusal.kind)
        assertTrue("busy" in busyAccounts.refusal.message)
        assertTrue(clock.now() - Gw.epoch >= AwaitAdmissionUseCase.MAX_WAIT)
        assertTrue(pauses.size in 30..120, "Polls slow down: ${pauses.size}")

        val blocked = ScriptedQueue(*Array(200) { AdmissionEntry.Waiting(blocked = true) })
        val limited = assertIs<InferencePlan.Refused>(await(blocked)(request, running = false) { ready })
        assertTrue("2 model calls at once" in limited.refusal.message, limited.refusal.message)

        val stillBusy = ScriptedQueue(*Array(200) { AdmissionEntry.Admitted })
        val model = assertIs<InferencePlan.Refused>(await(stillBusy)(request, running = false) { busy })
        assertEquals("claude-opus-5", model.model, "The model of the busy plan is recorded")
    }

    @Test
    fun pollsSlowDownWithJitter() {
        assertEquals(500.milliseconds, AwaitAdmissionUseCase.pollDelay(3))
        val medium = AwaitAdmissionUseCase.pollDelay(4)
        assertTrue(medium in 800.milliseconds..1200.milliseconds, "$medium")
        val long = AwaitAdmissionUseCase.pollDelay(10)
        assertTrue(long in 1600.milliseconds..2400.milliseconds, "$long")
    }

    @Test
    fun aRunningCallRenewsItsPlaceAndAlwaysGivesItBack() = runTest {
        val queue = ScriptedQueue()
        val answer = await(queue).holding(request) {
            delay(11.minutes)
            "done"
        }
        assertEquals("done", answer)
        assertEquals(listOf("renew", "renew", "release"), queue.calls)

        val failing = ScriptedQueue().apply { failure = IllegalStateException("database down") }
        assertEquals(1, await(failing).holding(request) { delay(6.minutes).let { 1 } }, "Left to the lease")
        assertEquals(listOf("renew", "release"), failing.calls)

        val gone = ScriptedQueue()
        val job = async { await(gone).holding(request) { delay(1.minutes) } }
        delay(1.seconds)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(listOf("release"), gone.calls, "A call cancelled gives its place back")

        val cancelledWrite = ScriptedQueue().apply { failure = CancellationException("stopped") }
        assertFailsWith<CancellationException> { await(cancelledWrite).holding(request) { delay(6.minutes) } }
    }

    @Test
    fun anyMemberReadsTheWaitingCounts() = runTest {
        assertEquals(QueueCounts(3, 1), GetInferenceQueueUseCaseImpl(ScriptedQueue())(Gw.memberA))
    }

    @Test
    fun noBudgetHoldIsKeptByDefault() = runTest {
        AllowAllSpend.release(Gw.orgA, Uuid.random())
    }
}
