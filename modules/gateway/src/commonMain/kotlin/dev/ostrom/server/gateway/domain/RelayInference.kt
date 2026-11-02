// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Bytes read from somewhere, chunk by chunk. */
fun interface ByteSource {
    /** Reads into [buffer]; returns the count read, or -1 at the end. */
    suspend fun read(buffer: ByteArray): Int
}

/** Bytes written somewhere. */
fun interface ByteSink {
    /** Writes the first [count] bytes of [bytes]. */
    suspend fun write(bytes: ByteArray, count: Int)
}

/**
 * A provider's answer, being received.
 *
 * @property status The HTTP status.
 * @property headers Names in lowercase.
 * @property body The body, as it arrives.
 */
class UpstreamAnswer(val status: Int, val headers: Map<String, String>, val body: ByteSource)

/**
 * The provider sent nothing for longer than the gateway waits: the call fails as the provider's failure, never as
 * cancelled, and a shared account it ran on rests a short while
 * ([AccountLease.stalled][dev.ostrom.server.sharing.domain.AccountLease.stalled]).
 */
class UpstreamStalledException : Exception("The provider stopped answering.")

/** Sends requests to providers; a provider silent too long is cut with [UpstreamStalledException]. */
interface InferenceUpstream {
    /** Sends [request] and hands the answer to [handle] while it streams; the connection closes after it. */
    suspend fun <T> exchange(request: UpstreamRequest, handle: suspend (UpstreamAnswer) -> T): T
}

/** Answers the agent. */
interface InferenceResponder {
    /** Answers the gateway's own refusal, in the shape the agent's API uses. */
    suspend fun refuse(protocol: WireProtocol, refusal: InferenceRefusal)

    /** Starts the answer and streams its body through [body]. */
    suspend fun stream(status: Int, headers: List<Pair<String, String>>, body: suspend (ByteSink) -> Unit)
}

/** Relays one model call of a member. */
interface RelayInferenceUseCase {
    /**
     * Relays it.
     *
     * @param caller The member.
     * @param call The call as the agent sent it.
     * @param responder Where the answer goes.
     */
    suspend operator fun invoke(caller: Caller, call: InferenceCall, responder: InferenceResponder)
}

/**
 * [RelayInferenceUseCase]: plans, sends, streams the answer back byte for byte while reading its usage, then records
 * the call. A shared account that answers 429 or 401 is observed (cool-down, refresh) and, before any byte reaches the
 * agent, the call moves to the next account, up to [MAX_ATTEMPTS] tries. One that stalls rests a short while, so the
 * next calls go elsewhere.
 *
 * @param planner Plans each try.
 * @param upstream Sends to the providers.
 * @param prices Prices the usage.
 * @param usage The usage ledger.
 * @param observer Traces.
 * @param clock The time.
 */
class RelayInferenceUseCaseImpl(
    private val planner: InferencePlanner,
    private val upstream: InferenceUpstream,
    private val prices: PriceTable,
    private val usage: ModelUsageSink,
    private val observer: ModelCallObserver,
    private val clock: Clock,
) : RelayInferenceUseCase {
    override suspend fun invoke(caller: Caller, call: InferenceCall, responder: InferenceResponder) {
        val exchange = Exchange(caller, call, responder, clock.now(), planner.configOf(caller), Uuid.random())
        var tries = 1
        var done = exchange.attempt(tries)
        while (done == null) {
            tries++
            done = exchange.attempt(tries)
        }
        record(done)
    }

    /** One call of an agent, over its tries; [id] names it in the ledger and holds its budget across the tries. */
    private inner class Exchange(
        val caller: Caller,
        val call: InferenceCall,
        val responder: InferenceResponder,
        val started: Instant,
        val org: GatewayOrgConfig,
        val id: Uuid,
    ) {
        /** One try: the finished call, or null to try again on another account. */
        suspend fun attempt(tries: Int): CallState? = when (val plan = plan()) {
            is InferencePlan.Refused -> refuse(plan)
            is InferencePlan.Ready -> send(plan, tries)
        }

        /**
         * Plans a try. Planning may fail after the budgets held the call's reserve (a lease that fails, the agent
         * leaving): the call is then recorded before the failure goes on, so that its record settles the hold rather
         * than leaving it to expire.
         */
        private suspend fun plan(): InferencePlan = try {
            planner.plan(caller, call, org, id)
        } catch (cancelled: CancellationException) {
            abandon(CANCELLED_STATUS, "cancelled")
            throw cancelled
        } catch (error: Exception) {
            abandon(FAILED_STATUS, "gateway_error")
            throw error
        }

        private suspend fun abandon(status: Int, outcome: String) {
            record(
                CallState(this, "", null, null).apply {
                    this.status = status
                    this.outcome = outcome
                },
            )
        }

        private suspend fun refuse(plan: InferencePlan.Refused): CallState {
            responder.refuse(call.protocol, plan.refusal)
            return CallState(this, plan.model, null, null).apply {
                status = plan.refusal.kind.status
                outcome = plan.refusal.kind.name.lowercase()
            }
        }

        private suspend fun send(plan: InferencePlan.Ready, tries: Int): CallState? {
            val state = CallState(this, plan.model, plan.provider, plan.lease?.let { it.account.id })
            // No `finally`: the compiler copies it per exit, and each copy needs its own test.
            val retry = try {
                upstream.exchange(plan.request) { answer -> relay(plan, answer, tries, state) }
            } catch (cancelled: CancellationException) {
                release(plan)
                state.outcome = "cancelled"
                record(state)
                throw cancelled
            } catch (_: UpstreamStalledException) {
                stop(plan, state, rest = true)
                false
            } catch (_: Exception) {
                stop(plan, state, rest = false)
                false
            }
            release(plan)
            return state.takeUnless { retry }
        }

        private suspend fun release(plan: InferencePlan.Ready) {
            plan.lease?.let { withContext(NonCancellable) { it.release() } }
        }

        /**
         * The call failed: [rest] rests a shared account whose provider stalled, before its slot goes back so that no
         * other call takes it in between; the rest failing or the agent leaving must not change this call's record.
         */
        private suspend fun stop(plan: InferencePlan.Ready, state: CallState, rest: Boolean) {
            if (rest) withContext(NonCancellable) { deliver { plan.lease?.stalled() } }
            release(plan)
            failed(state)
        }

        /** The provider failed: a 502 if the agent got nothing yet, otherwise the answer is cut. */
        private suspend fun failed(state: CallState) {
            if (state.answered) {
                state.outcome = if (state.agentGone) "agent_gone" else "upstream_broken"
            } else {
                val refusal =
                    InferenceRefusal(RefusalKind.UPSTREAM_UNREACHABLE, "The model provider could not be reached.")
                state.status = refusal.kind.status
                state.outcome = "upstream_unreachable"
                responder.refuse(call.protocol, refusal)
            }
        }

        /** Streams the answer; returns true when the call should go to another shared account instead. */
        private suspend fun relay(
            plan: InferencePlan.Ready,
            answer: UpstreamAnswer,
            tries: Int,
            state: CallState,
        ): Boolean {
            val lease = plan.lease
            lease?.observe(answer.status, answer.headers)
            val retry = lease != null && answer.status in RETRYABLE && tries < MAX_ATTEMPTS
            if (!retry) stream(answer, state)
            return retry
        }

        private suspend fun stream(answer: UpstreamAnswer, state: CallState) {
            state.status = answer.status
            state.outcome = if (answer.status in SUCCESS) "ok" else "provider_error"
            val meter = UsageMeter(call.protocol)
            val events = answer.headers["content-type"]?.startsWith(EVENT_STREAM) == true
            val whole = if (events) null else Accumulator()
            val buffer = ByteArray(CHUNK)
            // The first bytes are read before the answer starts: a provider failing before it sends any still gets
            // the agent a 502 it can retry, not an empty 200.
            var count = answer.body.read(buffer)
            while (count == 0) count = answer.body.read(buffer)
            state.answered = true
            responder.stream(answer.status, forwarded(answer.headers)) { sink ->
                while (count >= 0) {
                    if (count > 0) {
                        state.firstByte = state.firstByte ?: clock.now()
                        if (whole == null) meter.accept(buffer, count) else whole.add(buffer, count)
                        write(sink, buffer, count, state)
                    }
                    count = answer.body.read(buffer)
                }
            }
            whole?.bytes()?.let(meter::acceptBody)
            state.usage = meter.usage
        }

        /** Writes to the agent; a failure here is the agent gone, not the provider. */
        private suspend fun write(sink: ByteSink, buffer: ByteArray, count: Int, state: CallState) {
            try {
                sink.write(buffer, count)
            } catch (error: Exception) {
                // Cancelled: rethrown as such, and recorded as cancelled.
                currentCoroutineContext().ensureActive()
                state.agentGone = true
                throw error
            }
        }
    }

    /** What is known of a call as it goes. */
    private class CallState(
        val exchange: Exchange,
        val model: String,
        val provider: InferenceProvider?,
        val accountId: Uuid?,
    ) {
        var status: Int = 0
        var outcome: String = ""
        var answered: Boolean = false
        var agentGone: Boolean = false
        var firstByte: Instant? = null
        var usage: TokenUsage? = null
    }

    private suspend fun record(state: CallState) {
        val started = state.exchange.started
        val tokens = state.usage
        val cost = state.provider?.let { provider -> tokens?.let { prices.costMicros(provider, state.model, it) } }
        val record = ModelCallRecord(
            id = state.exchange.id,
            orgId = state.exchange.caller.orgId,
            memberId = state.exchange.caller.memberId,
            sessionId = state.exchange.call.sessionId,
            protocol = state.exchange.call.protocol,
            provider = state.provider,
            accountId = state.accountId,
            model = state.model,
            usage = tokens,
            costMicros = cost ?: 0,
            priced = cost != null,
            status = state.status,
            outcome = state.outcome,
            startedAt = started,
            firstByteMillis = state.firstByte?.let { (it - started).inWholeMilliseconds },
            durationMillis = (clock.now() - started).inWholeMilliseconds,
            launchId = state.exchange.caller.launchId,
        )
        // Never cancelled halfway: the ledger keeps a call the agent abandoned.
        withContext(NonCancellable) {
            deliver { usage.record(record) }
            deliver { observer.observe(record) }
        }
    }

    /** Runs [block]; the ledger or the traces being down must not fail a call already answered. */
    private suspend fun deliver(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /** A non-streamed body, kept (up to [MAX_BODY] bytes) only to read its usage at the end. */
    private class Accumulator {
        private var bytes = ByteArray(INITIAL_BODY)
        private var size = 0
        private var overflow = false

        fun add(chunk: ByteArray, count: Int) {
            overflow = overflow || size + count > MAX_BODY
            if (!overflow) {
                if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(size + count, bytes.size * 2))
                chunk.copyInto(bytes, size, 0, count)
                size += count
            }
        }

        fun bytes(): ByteArray? = if (overflow) null else bytes.copyOf(size)
    }

    /** Tries and limits. */
    companion object {
        /** Tries per call when shared accounts refuse. */
        const val MAX_ATTEMPTS = 3

        /** A non-streamed answer larger than this is relayed but not metered. */
        const val MAX_BODY = 8 * 1024 * 1024

        /** Recorded for a call the agent left while it was planned (as proxies record a client gone). */
        private const val CANCELLED_STATUS = 499

        /** Recorded for a call whose planning failed: the agent gets the server's 500. */
        private const val FAILED_STATUS = 500

        private const val CHUNK = 16 * 1024
        private const val INITIAL_BODY = 8 * 1024
        private const val EVENT_STREAM = "text/event-stream"
        private val SUCCESS = 200..299
        private val RETRYABLE = setOf(401, 429)

        // Rate limits only: `openai-organization` and `openai-project` name the org behind a key, `x-codex-*` a shared
        // account's own usage.
        private val FORWARDED_PREFIXES = listOf("anthropic-ratelimit-", "x-ratelimit-")
        private val FORWARDED = setOf(
            "content-type",
            "retry-after",
            "request-id",
            "x-request-id",
            // A stream must not be buffered by a proxy between the gateway and the agent.
            "cache-control",
            "x-accel-buffering",
        )

        /** The answer headers an agent may see: content type, request ids, rate limits, stream caching. */
        fun forwarded(headers: Map<String, String>): List<Pair<String, String>> =
            headers.filter { (name, _) -> name in FORWARDED || FORWARDED_PREFIXES.any { name.startsWith(it) } }
                .toList()
    }
}
