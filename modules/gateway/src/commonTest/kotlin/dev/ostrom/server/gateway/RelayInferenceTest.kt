// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.AllowAllSpend
import dev.ostrom.server.gateway.domain.InferenceCall
import dev.ostrom.server.gateway.domain.InferenceProvider
import dev.ostrom.server.gateway.domain.InferenceRefusal
import dev.ostrom.server.gateway.domain.InferenceResponder
import dev.ostrom.server.gateway.domain.InstanceProviderKeys
import dev.ostrom.server.gateway.domain.ModelCallObserver
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.ModelCallTrace
import dev.ostrom.server.gateway.domain.ModelCallTracer
import dev.ostrom.server.gateway.domain.ModelUsageSink
import dev.ostrom.server.gateway.domain.NoModelCallObserver
import dev.ostrom.server.gateway.domain.NoModelUsageSink
import dev.ostrom.server.gateway.domain.PriceTable
import dev.ostrom.server.gateway.domain.RefusalKind
import dev.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.gateway.domain.TokenUsage
import dev.ostrom.server.gateway.domain.UpstreamAnswer
import dev.ostrom.server.gateway.domain.UpstreamStalledException
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.SharedAccountPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The relay with a fake provider: bytes untouched, usage metered, retries on shared accounts, failures recorded. */
class RelayInferenceTest {
    private val clock = TestClock()
    private val settings = InMemorySettings()
    private val accounts = InMemoryAccounts()
    private val shared: SharedAccountPool = pool(accounts, clock)
    private val records = mutableListOf<ModelCallRecord>()
    private val observed = mutableListOf<ModelCallRecord>()
    private var sink = ModelUsageSink { records += it }

    private fun relay(upstream: FakeUpstream, keys: InstanceProviderKeys = InstanceProviderKeys(anthropic = "sk-an")) =
        RelayInferenceUseCaseImpl(
            planner(settings, shared, keys = keys),
            upstream,
            PriceTable(),
            { sink.record(it) },
            { observed += it },
            clock,
        )

    private fun call(
        body: String = """{"model":"claude-opus-5","stream":true}""",
        protocol: WireProtocol = WireProtocol.MESSAGES,
    ) = InferenceCall(protocol, body, emptyMap(), "session-1")

    private val events = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":1000000,"output_tokens":1}}}

        data: {"type":"message_delta","usage":{"output_tokens":1000000}}

    """.trimIndent().encodeToByteArray()

    private fun eventAnswer(status: Int = 200, chunks: List<ByteArray> = listOf(events)) = UpstreamAnswer(
        status,
        mapOf(
            "content-type" to "text/event-stream; charset=utf-8",
            "request-id" to "req_1",
            "anthropic-ratelimit-unified-5h-utilization" to "0.5",
            "set-cookie" to "secret",
        ),
        source(*chunks.toTypedArray()),
    )

    @Test
    fun aStreamIsRelayedByteForByteAndMetered() = runTest {
        val responder = RecordingResponder()
        val upstream =
            FakeUpstream(
                eventAnswer(200, listOf(events.copyOfRange(0, 10), ByteArray(0), events.copyOfRange(10, events.size))),
            )
        relay(upstream)(Gw.memberA.copy(launchId = "lch_1", workflowId = "review"), call(), responder)
        assertEquals(events.decodeToString(), responder.text)
        assertEquals(200, responder.status)
        assertEquals(
            listOf("content-type", "request-id", "anthropic-ratelimit-unified-5h-utilization"),
            responder.headers.map { it.first },
            "Only safe headers reach the agent",
        )
        val record = records.single()
        assertEquals(TokenUsage(input = 1_000_000, output = 1_000_000), record.usage)
        assertEquals(5_000_000L + 25_000_000, record.costMicros)
        assertTrue(record.priced)
        assertEquals("ok", record.outcome)
        assertEquals(InferenceProvider.ANTHROPIC, record.provider)
        assertEquals(0L, record.firstByteMillis)
        assertEquals(Gw.orgA, record.orgId)
        assertEquals(Gw.memberA.memberId, record.memberId)
        assertEquals("lch_1", record.launchId, "An agent token's call names its launch")
        assertEquals("review", record.workflowId, "And the workflow its launch plays")
        assertEquals("session-1", record.sessionId)
        assertNull(record.accountId)
        assertEquals(listOf(record), observed)
        assertEquals("https://anthropic.test/v1/messages", upstream.requests.single().url)
    }

    @Test
    fun aWholeAnswerIsMeteredAtTheEnd() = runTest {
        val body = """{"usage":{"input_tokens":10,"output_tokens":2}}""".encodeToByteArray()
        for (headers in listOf(mapOf("content-type" to "application/json"), emptyMap())) {
            val responder = RecordingResponder()
            relay(FakeUpstream(UpstreamAnswer(200, headers, source(body))))(Gw.memberA, call(), responder)
            assertEquals(body.decodeToString(), responder.text)
        }
        assertEquals(
            listOf(TokenUsage(input = 10, output = 2), TokenUsage(input = 10, output = 2)),
            records.map {
                it.usage
            },
        )
        val unpriced = RecordingResponder()
        relay(FakeUpstream(UpstreamAnswer(500, emptyMap(), source("{}".encodeToByteArray()))))(
            Gw.memberA,
            call("""{"model":"claude-unknown"}"""),
            unpriced,
        )
        assertEquals(500, unpriced.status)
        val failed = records.last()
        assertEquals("provider_error", failed.outcome)
        relay(FakeUpstream(UpstreamAnswer(199, emptyMap(), source("{}".encodeToByteArray()))))(
            Gw.memberA,
            call(),
            RecordingResponder(),
        )
        assertEquals("provider_error", records.last().outcome, "Below 200 is no success either")
        assertNull(failed.usage)
        assertEquals(0L, failed.costMicros)
        assertEquals(false, failed.priced)
    }

    @Test
    fun aHugeWholeAnswerIsRelayedButNotMetered() = runTest {
        val chunk = ByteArray(1024 * 1024) { ' '.code.toByte() }
        val chunks = Array(RelayInferenceUseCaseImpl.MAX_BODY / chunk.size + 1) { chunk }
        val responder = RecordingResponder()
        relay(FakeUpstream(UpstreamAnswer(200, mapOf("content-type" to "application/json"), source(*chunks))))(
            Gw.memberA,
            call(),
            responder,
        )
        assertEquals(chunks.size * chunk.size, responder.size)
        assertNull(records.single().usage)
    }

    @Test
    fun theGatewayAnswersItselfWhenThePlanRefuses() = runTest {
        val responder = RecordingResponder()
        relay(FakeUpstream())(Gw.memberA, call("nope"), responder)
        assertEquals(RefusalKind.INVALID, responder.refusal?.kind)
        assertEquals(WireProtocol.MESSAGES, responder.protocol)
        val record = records.single()
        assertEquals(400, record.status)
        assertEquals("invalid", record.outcome)
        assertNull(record.provider)
        assertNull(record.firstByteMillis)
    }

    @Test
    fun aSharedAccountThatRefusesHandsTheCallToTheNext() = runTest {
        val first = accounts.share(AccountProvider.CLAUDE, "first", clock)
        val second = accounts.share(AccountProvider.CLAUDE, "second", clock)
        val tooMany = UpstreamAnswer(429, mapOf("retry-after" to "30"), source())
        val upstream = FakeUpstream(tooMany, eventAnswer())
        val responder = RecordingResponder()
        relay(upstream, InstanceProviderKeys())(Gw.memberA, call(), responder)
        assertEquals(200, responder.status)
        assertEquals(2, upstream.requests.size)
        val used = records.single().accountId
        val cooled = accounts.accounts.single { it.cooldownUntil != null }.id
        assertEquals(setOf(first.id, second.id), setOf(used, cooled))
        assertEquals(InferenceProvider.CLAUDE_SHARED, records.single().provider)
        assertEquals(0, shared.inFlight(first.id) + shared.inFlight(second.id), "Every slot is given back")
    }

    @Test
    fun afterThreeRefusalsTheLastOneReachesTheAgent() = runTest {
        repeat(3) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val upstream = FakeUpstream(
            UpstreamAnswer(401, emptyMap(), source()),
            UpstreamAnswer(429, emptyMap(), source()),
            UpstreamAnswer(
                429,
                mapOf("content-type" to "application/json"),
                source("""{"error":{}}""".encodeToByteArray()),
            ),
        )
        val responder = RecordingResponder()
        relay(upstream, InstanceProviderKeys())(Gw.memberA, call(), responder)
        assertEquals(RelayInferenceUseCaseImpl.MAX_ATTEMPTS, upstream.requests.size)
        assertEquals(1, settings.callReads, "The org is read once, not once per try")
        assertEquals(429, responder.status)
        assertEquals("""{"error":{}}""", responder.text)
        assertEquals("provider_error", records.single().outcome)
    }

    @Test
    fun aSingleCoolingAccountWithoutKeyAnswersWhenToRetry() = runTest {
        accounts.share(AccountProvider.CLAUDE, "only", clock)
        val responder = RecordingResponder()
        relay(FakeUpstream(UpstreamAnswer(429, mapOf("retry-after" to "30"), source())), InstanceProviderKeys())(
            Gw.memberA,
            call(),
            responder,
        )
        assertEquals(RefusalKind.NO_CAPACITY, responder.refusal?.kind)
        assertEquals(clock.now() + 30_000.milliseconds, responder.refusal?.retryAt)
        assertEquals("no_capacity", records.single().outcome)
    }

    @Test
    fun anUnreachableProviderIsA502AndABrokenStreamIsCut() = runTest {
        val unreachable = RecordingResponder()
        relay(FakeUpstream(IllegalStateException("connection refused")))(Gw.memberA, call(), unreachable)
        assertEquals(RefusalKind.UPSTREAM_UNREACHABLE, unreachable.refusal?.kind)
        assertEquals(502, records.last().status)
        assertEquals("upstream_unreachable", records.last().outcome)

        val gone = RecordingResponder().apply { failAfterWrites = 1 }
        relay(FakeUpstream(eventAnswer(200, listOf(events.copyOfRange(0, 5), events.copyOfRange(5, events.size)))))(
            Gw.memberA,
            call(),
            gone,
        )
        assertNull(gone.refusal, "No error after the answer started")
        assertEquals("agent_gone", records.last().outcome, "The agent left: not the provider's fault")
        assertNotNull(records.last().firstByteMillis)

        val cut = RecordingResponder()
        val broken = FailingSource(listOf(ByteArray(0), events.copyOfRange(0, 5)), IllegalStateException("reset"))
        relay(FakeUpstream(UpstreamAnswer(200, mapOf("content-type" to "text/event-stream"), broken)))(
            Gw.memberA,
            call(),
            cut,
        )
        assertNull(cut.refusal)
        assertEquals(5, cut.size, "An empty read is not the start of the answer")
        assertEquals("upstream_broken", records.last().outcome)
    }

    @Test
    fun aSharedAccountWhoseProviderStalledRestsAMinuteAndTheNextCallsGoElsewhere() = runTest {
        val first = accounts.share(AccountProvider.CLAUDE, "first", clock)
        val second = accounts.share(AccountProvider.CLAUDE, "second", clock)
        val upstream = FakeUpstream(UpstreamStalledException(), eventAnswer())
        val cut = RecordingResponder()
        relay(upstream, InstanceProviderKeys())(Gw.memberA, call(), cut)
        assertEquals(RefusalKind.UPSTREAM_UNREACHABLE, cut.refusal?.kind, "Cut before the answer: a 502")
        val stalled = records.single().accountId
        val rest = clock.now() + SharedAccountPool.STALL_COOLDOWN
        assertEquals(rest, accounts.accounts.single { it.id == stalled }.cooldownUntil)

        val next = RecordingResponder()
        relay(upstream, InstanceProviderKeys())(Gw.memberA, call(), next)
        assertEquals(200, next.status)
        assertEquals(
            setOf(first.id, second.id),
            setOf(stalled, records.last().accountId),
            "The next call goes elsewhere",
        )
        assertEquals(0, shared.inFlight(first.id) + shared.inFlight(second.id), "Every slot is given back")
    }

    @Test
    fun aStallMidStreamRestsTheAccountAndAStallOnAKeyRestsNothing() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "only", clock)
        val broken = FailingSource(listOf(events.copyOfRange(0, 5)), UpstreamStalledException())
        val cut = RecordingResponder()
        relay(
            FakeUpstream(UpstreamAnswer(200, mapOf("content-type" to "text/event-stream"), broken)),
            InstanceProviderKeys(),
        )(
            Gw.memberA,
            call(),
            cut,
        )
        assertEquals(5, cut.size)
        assertEquals("upstream_broken", records.last().outcome)
        assertEquals(clock.now() + SharedAccountPool.STALL_COOLDOWN, accounts.accounts.single().cooldownUntil)
        assertEquals(account.id, records.last().accountId)

        val onKey = RecordingResponder()
        relay(FakeUpstream(UpstreamStalledException()))(Gw.memberA, call(), onKey)
        assertEquals(RefusalKind.UPSTREAM_UNREACHABLE, onKey.refusal?.kind)
        assertNull(records.last().accountId, "An API key has no account to rest")
    }

    @Test
    fun aProviderFailingBeforeItsFirstByteIsStillA502() = runTest {
        val responder = RecordingResponder()
        val silent = FailingSource(emptyList(), IllegalStateException("reset before the body"))
        relay(FakeUpstream(UpstreamAnswer(200, mapOf("content-type" to "text/event-stream"), silent)))(
            Gw.memberA,
            call(),
            responder,
        )
        assertNull(responder.status, "The answer never started")
        assertEquals(RefusalKind.UPSTREAM_UNREACHABLE, responder.refusal?.kind)
        assertEquals(502, records.single().status)
        assertEquals("upstream_unreachable", records.single().outcome)
    }

    @Test
    fun aCancelledCallIsRecordedAndStaysCancelled() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "max", clock)
        assertFailsWith<CancellationException> {
            relay(
                FakeUpstream(CancellationException("agent left")),
                InstanceProviderKeys(),
            )(Gw.memberA, call(), RecordingResponder())
        }
        assertEquals("cancelled", records.single().outcome)
        assertEquals(account.id, records.single().accountId)
        assertEquals(0, shared.inFlight(account.id))
    }

    @Test
    fun aLedgerDownNeverFailsTheCallButCancellationPasses() = runTest {
        sink = ModelUsageSink { error("ledger down") }
        val responder = RecordingResponder()
        relay(FakeUpstream(eventAnswer()))(Gw.memberA, call(), responder)
        assertEquals(200, responder.status)
        sink = ModelUsageSink { throw CancellationException("stopping") }
        assertFailsWith<CancellationException> {
            relay(FakeUpstream(eventAnswer()))(Gw.memberA, call(), RecordingResponder())
        }
        val observerDown = RelayInferenceUseCaseImpl(
            planner(settings, pool(accounts, clock), keys = InstanceProviderKeys(anthropic = "sk-an")),
            FakeUpstream(eventAnswer()),
            PriceTable(),
            NoModelUsageSink,
            ModelCallObserver { error("traces down") },
            clock,
        )
        observerDown(Gw.memberA, call(), RecordingResponder())
    }

    @Test
    fun theDefaultsAllowAndDropEverything() = runTest {
        assertEquals(SpendDecision.Allowed, AllowAllSpend.check(SpendRequest(Gw.orgA, Gw.memberA.memberId, Gw.orgA, 1)))
        val record = ModelCallRecord(
            Gw.orgA, Gw.orgA, Gw.memberA.memberId, null, WireProtocol.CHAT, null, null, "m", null, 0, false, 400,
            "invalid", clock.now(), null, 0,
        )
        NoModelUsageSink.record(record)
        NoModelCallObserver.observe(record)
        assertEquals(
            listOf("content-type" to "a", "cache-control" to "no-cache", "x-ratelimit-limit" to "1"),
            RelayInferenceUseCaseImpl.forwarded(
                linkedMapOf(
                    "content-type" to "a",
                    "cache-control" to "no-cache",
                    "server" to "x",
                    "x-ratelimit-limit" to "1",
                    // The org behind a key and a shared account's own usage stay with the gateway.
                    "openai-organization" to "org-instance",
                    "openai-project" to "proj",
                    "x-codex-primary-used-percent" to "80",
                ),
            ),
        )
    }

    @Test
    fun eachCallIsTracedOnceOverItsTriesAndItsTraceEndsWithItsRecord() = runTest {
        repeat(2) { accounts.share(AccountProvider.CLAUDE, "t$it", clock) }
        var started = 0
        val ended = mutableListOf<ModelCallRecord>()
        val tracer = ModelCallTracer {
            started++
            ModelCallTrace { ended += it }
        }
        var spend = SpendGuard { SpendDecision.Allowed }
        val relay = RelayInferenceUseCaseImpl(
            planner(settings, shared, spend = { spend.check(it) }),
            FakeUpstream(UpstreamAnswer(429, emptyMap(), source()), eventAnswer()),
            PriceTable(),
            { records += it },
            { },
            clock,
            tracer,
        )
        relay(Gw.memberA, call(), RecordingResponder())
        assertEquals(1, started, "One trace for the two tries")
        assertEquals(records, ended)
        assertEquals(listOf(200), ended.map { it.status })

        spend = SpendGuard { throw CancellationException("agent gone") }
        assertFailsWith<CancellationException> { relay(Gw.memberA, call(), RecordingResponder()) }
        assertEquals(2, started)
        assertEquals(records, ended, "A call abandoned while planned ends its trace too")
        assertEquals(listOf(200, 499), ended.map { it.status })
    }

    @Test
    fun aCallWhoseAnswerFailsAfterItsPlanIsRecordedOnceAndEndsItsTrace() = runTest {
        val ended = mutableListOf<ModelCallRecord>()
        val responder = object : InferenceResponder by RecordingResponder() {
            override suspend fun refuse(protocol: WireProtocol, refusal: InferenceRefusal) {
                throw CancellationException("agent gone while answered")
            }
        }
        val relay = RelayInferenceUseCaseImpl(
            planner(settings, shared, spend = { SpendDecision.Insufficient("Budget left too low for this call") }),
            FakeUpstream(),
            PriceTable(),
            { records += it },
            { },
            clock,
            { ModelCallTrace { ended += it } },
        )
        assertFailsWith<CancellationException> { relay(Gw.memberA, call(), responder) }
        assertEquals(listOf(499 to "cancelled"), records.map { it.status to it.outcome }, "Its hold is settled")
        assertEquals(records, ended)
    }

    @Test
    fun tracingThatFailsToStartNeverFailsTheCall() = runTest {
        fun relay(tracer: ModelCallTracer) = RelayInferenceUseCaseImpl(
            planner(settings, shared, keys = InstanceProviderKeys(anthropic = "sk-an")),
            FakeUpstream(eventAnswer()),
            PriceTable(),
            { records += it },
            { },
            clock,
            tracer,
        )
        relay(ModelCallTracer { error("tracer down") })(Gw.memberA, call(), RecordingResponder())
        assertEquals(listOf("ok"), records.map { it.outcome }, "Relayed and recorded, untraced")
        val gone = relay(ModelCallTracer { throw CancellationException("request gone") })
        assertFailsWith<CancellationException> { gone(Gw.memberA, call(), RecordingResponder()) }
        assertEquals(1, records.size, "A cancelled start is no call")
    }
}
