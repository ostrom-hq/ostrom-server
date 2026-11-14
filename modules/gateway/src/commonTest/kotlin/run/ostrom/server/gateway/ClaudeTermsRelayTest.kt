// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCase
import run.ostrom.server.gateway.domain.ByteSource
import run.ostrom.server.gateway.domain.InferenceCall
import run.ostrom.server.gateway.domain.InferencePlan
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.InferenceRefusal
import run.ostrom.server.gateway.domain.InstanceProviderKeys
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import run.ostrom.server.gateway.domain.UpstreamAnswer
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.ClaudeAccountRefusals
import run.ostrom.server.sharing.domain.SharedAccountPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The public provider error: https://github.com/anthropics/claude-code/issues/57569.
private const val TERMS = "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in " +
    "claude.ai with the email in /status to continue."

/** Claude's error envelope with [message]. */
private fun claudeError(message: String = TERMS) = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", "invalid_request_error")
        put("message", message)
    }
}.toString()

/** TeamCode #277: Claude's terms refusal rests only that account and the call tries the others, safely. */
class ClaudeTermsRelayTest {
    private val clock = TestClock()
    private val settings = InMemorySettings()
    private val accounts = InMemoryAccounts()
    private val shared: SharedAccountPool = pool(accounts, clock)
    private val records = mutableListOf<ModelCallRecord>()

    private fun relay(
        upstream: FakeUpstream,
        keys: InstanceProviderKeys = InstanceProviderKeys(),
        admission: AwaitAdmissionUseCase? = null,
    ) = RelayInferenceUseCaseImpl(
        planner(settings, shared, keys = keys),
        upstream,
        PriceTable(),
        { records += it },
        { },
        clock,
        admission = admission,
    )

    private fun call() =
        InferenceCall(WireProtocol.MESSAGES, """{"model":"claude-opus-5","stream":true}""", emptyMap(), null)

    private fun terms(body: String = claudeError()) =
        UpstreamAnswer(400, mapOf("content-type" to "application/json"), source(body.encodeToByteArray()))

    private val events = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

    private fun served() =
        UpstreamAnswer(200, mapOf("content-type" to "text/event-stream"), source(events.encodeToByteArray()))

    private val cooled get() = accounts.accounts.filter { it.cooldownUntil != null }

    @Test
    fun theRefusingAccountRestsAMinuteAndTheCallGoesToAnotherWithoutShowingTheRefusal() = runTest {
        repeat(3) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val upstream = FakeUpstream(terms(), served())
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(2, upstream.requests.size)
        assertEquals(200, responder.status)
        assertEquals(events, responder.text, "Only the second account's answer reaches the agent")
        assertEquals(clock.now() + 60.seconds, cooled.single().cooldownUntil)
        assertTrue(cooled.single().id != records.single().accountId)
        assertEquals("ok", records.single().outcome)
        assertEquals(0, shared.inFlight(Gw.orgA).values.sum(), "Every slot is given back")
    }

    @Test
    fun withoutAnotherAccountTheAgentGetsOstromsExplanationNotTheProvidersRefusal() = runTest {
        repeat(3) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val upstream = FakeUpstream(terms(), terms(), terms())
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(3, upstream.requests.size, "Each account once, then no account is left")
        assertEquals(3, cooled.size)
        assertTerms(responder.refusal)
        assertNull(responder.status, "Nothing of the provider's answer is relayed")
        assertEquals("provider_terms_required", records.single().outcome)
        assertEquals(503, records.single().status)
    }

    @Test
    fun accountsAwaitingTheirOwnerNeverHideAHealthyOneBeyondThreeTries() = runTest {
        repeat(5) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val upstream = FakeUpstream(terms(), terms(), terms(), terms(), served())
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(5, upstream.requests.size, "TeamCode's bound is 64 accounts, not the three of other refusals")
        assertEquals(200, responder.status)
        assertEquals(4, cooled.size)
    }

    @Test
    fun aCallStopsAtSixtyFourAccountsAwaitingTheirOwner() = runTest {
        val bound = RelayInferenceUseCaseImpl.MAX_ACCOUNT_ACTION_TRIES
        repeat(bound + 1) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val upstream = FakeUpstream(*Array(bound + 1) { terms() })
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(bound, upstream.requests.size)
        assertTerms(responder.refusal)
    }

    @Test
    fun aRestThatCannotBeWrittenStopsTheCallRatherThanMeetTheSameAccountAgain() = runTest {
        repeat(2) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        accounts.answerWrites = IllegalStateException("lock wait timeout")
        val upstream = FakeUpstream(terms(), served())
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(1, upstream.requests.size)
        assertTerms(responder.refusal)
        assertEquals("provider_terms_required", records.single().outcome)
    }

    @Test
    fun theOnlyAccountRestingExplainsItselfRatherThanAFullPool() = runTest {
        accounts.share(AccountProvider.CLAUDE, "only", clock)
        val upstream = FakeUpstream(terms())
        val responder = RecordingResponder()
        relay(upstream)(Gw.memberA, call(), responder)
        assertEquals(1, upstream.requests.size)
        assertTerms(responder.refusal)
        assertEquals("provider_terms_required", records.single().outcome)
    }

    @Test
    fun aKeyStillServesTheCallAfterTheRefusal() = runTest {
        accounts.share(AccountProvider.CLAUDE, "only", clock)
        val upstream = FakeUpstream(terms(), served())
        val responder = RecordingResponder()
        relay(upstream, InstanceProviderKeys(anthropic = "sk-an"))(Gw.memberA, call(), responder)
        assertEquals(200, responder.status)
        assertEquals(InferenceProvider.ANTHROPIC, records.single().provider)
    }

    @Test
    fun aQueuesRefusalAfterwardsStaysTheQueues() = runTest {
        repeat(2) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val timeout = InferenceRefusal(RefusalKind.QUEUE_TIMEOUT, "waited")
        val queue = object : AwaitAdmissionUseCase {
            override suspend fun invoke(
                request: AdmissionRequest,
                running: Boolean,
                start: suspend () -> InferencePlan,
            ) = if (running) InferencePlan.Refused(timeout, "") else start()

            override suspend fun <T> holding(request: AdmissionRequest, block: suspend () -> T): T = block()
        }
        val responder = RecordingResponder()
        relay(FakeUpstream(terms()), admission = queue)(Gw.memberA, call(), responder)
        assertEquals(timeout, responder.refusal)
    }

    @Test
    fun anyOtherBadRequestIsRelayedWholeAndRestsNothing() = runTest {
        val large = claudeError("x".repeat(ClaudeAccountRefusals.MAX_BYTES))
        for (body in listOf(
            claudeError("Invalid signature in thinking block"),
            claudeError("Invalid tools.0.description: $TERMS"),
            claudeError() + " ".repeat(ClaudeAccountRefusals.MAX_BYTES),
            large,
            "not json",
            "",
        )) {
            accounts.accounts.clear()
            records.clear()
            repeat(2) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
            val chunked = body.encodeToByteArray().toList().chunked(1000).map { it.toByteArray() }
            val upstream = FakeUpstream(UpstreamAnswer(400, emptyMap(), source(*chunked.toTypedArray())))
            val responder = RecordingResponder()
            relay(upstream)(Gw.memberA, call(), responder)
            assertEquals(1, upstream.requests.size)
            assertEquals(400, responder.status)
            assertEquals(body, responder.text, "Byte for byte, the bytes read first included")
            assertTrue(cooled.isEmpty())
            assertEquals("provider_error", records.single().outcome)
        }
    }

    @Test
    fun aKeysBadRequestIsNeverRead() = runTest {
        val upstream = FakeUpstream(terms())
        val responder = RecordingResponder()
        relay(upstream, InstanceProviderKeys(anthropic = "sk-an"))(Gw.memberA, call(), responder)
        assertEquals(400, responder.status, "No account to rest, nothing to fail over to")
        assertEquals(claudeError(), responder.text)
    }

    @Test
    fun aBodyThatNeverEndsIsReadTwoSecondsThenRelayed() = runTest {
        accounts.share(AccountProvider.CLAUDE, "only", clock)
        val start = claudeError().encodeToByteArray()
        var reads = 0
        val slow = ByteSource { buffer ->
            when (reads++) {
                0 -> start.size.also { start.copyInto(buffer) }
                1, 2 -> {
                    delay(10.seconds)
                    -1
                }
                else -> -1
            }
        }
        val responder = RecordingResponder()
        val before = testScheduler.currentTime
        relay(FakeUpstream(UpstreamAnswer(400, emptyMap(), slow)))(Gw.memberA, call(), responder)
        val waited = testScheduler.currentTime - before
        assertEquals(ClaudeAccountRefusals.READ_TIMEOUT.inWholeMilliseconds + 10_000, waited)
        assertEquals(400, responder.status, "An unfinished body is no account refusal")
        assertEquals(claudeError(), responder.text)
        assertTrue(cooled.isEmpty())
    }

    @Test
    fun theAgentLeavingWhileTheErrorIsReadCancelsTheCall() = runTest {
        accounts.share(AccountProvider.CLAUDE, "only", clock)
        val stuck = ByteSource { awaitCancellation() }
        val relaying = async {
            relay(FakeUpstream(UpstreamAnswer(400, emptyMap(), stuck)))(Gw.memberA, call(), RecordingResponder())
        }
        runCurrent()
        relaying.cancel()
        assertFailsWith<CancellationException> { relaying.await() }
        assertEquals("cancelled", records.single().outcome)
        assertTrue(cooled.isEmpty())
        assertEquals(0, shared.inFlight(Gw.orgA).values.sum())
    }

    private fun assertTerms(refusal: InferenceRefusal?) {
        assertEquals(RefusalKind.PROVIDER_TERMS_REQUIRED, refusal?.kind)
        val message = refusal!!.message
        assertTrue("https://claude.ai" in message && "accept" in message, message)
        assertFalse("/status" in message, "The provider's text is never forwarded")
        assertFalse(refusal.shouldRetry(clock.now()), "x-should-retry: false")
        assertEquals(503, refusal.kind.status)
        assertEquals("api_error", refusal.kind.anthropicType)
        assertEquals("server_error", refusal.kind.openAiType)
    }
}
