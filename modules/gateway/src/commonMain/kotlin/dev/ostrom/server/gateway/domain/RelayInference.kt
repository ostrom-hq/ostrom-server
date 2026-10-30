package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
 * @property headers Names in lowercase.
 */
class UpstreamAnswer(val status: Int, val headers: Map<String, String>, val body: ByteSource)

/** Sends requests to providers. */
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
     * @param protocol The API shape.
     * @param body The request body.
     * @param headers The agent's headers, names in lowercase.
     * @param sessionId The agent session, when known.
     * @param responder Where the answer goes.
     */
    suspend operator fun invoke(
        caller: Caller,
        protocol: WireProtocol,
        body: String,
        headers: Map<String, String>,
        sessionId: String?,
        responder: InferenceResponder,
    )
}

/**
 * [RelayInferenceUseCase]: plans, sends, streams the answer back byte for byte while reading its usage, then records
 * the call. A shared account that answers 429 or 401 before any byte reaches the agent is observed (cool-down,
 * refresh) and the call moves to the next account, up to [MAX_ATTEMPTS] tries.
 */
class RelayInferenceUseCaseImpl(
    private val planner: InferencePlanner,
    private val upstream: InferenceUpstream,
    private val prices: PriceTable,
    private val usage: ModelUsageSink,
    private val observer: ModelCallObserver,
    private val clock: Clock,
) : RelayInferenceUseCase {
    override suspend fun invoke(
        caller: Caller,
        protocol: WireProtocol,
        body: String,
        headers: Map<String, String>,
        sessionId: String?,
        responder: InferenceResponder,
    ) {
        val started = clock.now()
        var attempt = 0
        while (true) {
            attempt++
            val plan = planner.plan(caller, protocol, body, headers, sessionId)
            if (plan is InferencePlan.Refused) {
                responder.refuse(protocol, plan.refusal)
                record(Call(caller, protocol, sessionId, started, plan.model).apply { status = plan.refusal.kind.status; outcome = plan.refusal.kind.name.lowercase() })
                return
            }
            plan as InferencePlan.Ready
            val call = Call(caller, protocol, sessionId, started, plan.model).apply {
                provider = plan.provider
                accountId = plan.lease?.account?.id
            }
            val retry = try {
                upstream.exchange(plan.request) { answer -> relay(plan, answer, attempt, call, responder) }
            } catch (cancelled: CancellationException) {
                call.outcome = "cancelled"
                record(call)
                throw cancelled
            } catch (_: Exception) {
                if (call.firstByte == null) {
                    call.status = RefusalKind.UPSTREAM_UNREACHABLE.status
                    call.outcome = "upstream_unreachable"
                    responder.refuse(protocol, InferenceRefusal(RefusalKind.UPSTREAM_UNREACHABLE, "The model provider could not be reached."))
                } else {
                    call.outcome = "upstream_broken"
                }
                false
            } finally {
                plan.lease?.release()
            }
            if (retry) continue
            record(call)
            return
        }
    }

    /** Streams the answer; returns true when the call should go to another shared account instead. */
    private suspend fun relay(plan: InferencePlan.Ready, answer: UpstreamAnswer, attempt: Int, call: Call, responder: InferenceResponder): Boolean {
        plan.lease?.observe(answer.status, answer.headers)
        if (plan.lease != null && answer.status in RETRYABLE && attempt < MAX_ATTEMPTS) return true
        call.status = answer.status
        call.outcome = if (answer.status in 200..299) "ok" else "provider_error"
        val meter = UsageMeter(call.protocol)
        val streamed = answer.headers["content-type"]?.startsWith("text/event-stream") == true
        val whole = if (streamed) null else Accumulator()
        responder.stream(answer.status, forwarded(answer.headers)) { sink ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val count = answer.body.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (call.firstByte == null) call.firstByte = clock.now()
                if (whole == null) meter.accept(buffer, count) else whole.add(buffer, count)
                sink.write(buffer, count)
            }
        }
        whole?.bytes()?.let(meter::acceptBody)
        call.usage = meter.usage
        return false
    }

    private suspend fun record(call: Call) {
        val tokens = call.usage
        val provider = call.provider
        val cost = if (tokens != null && provider != null) prices.costMicros(provider, call.model, tokens) else null
        val ended = clock.now()
        val record = ModelCallRecord(
            id = Uuid.random(),
            orgId = call.caller.orgId,
            memberId = call.caller.memberId,
            sessionId = call.sessionId,
            protocol = call.protocol,
            provider = call.provider,
            accountId = call.accountId,
            model = call.model,
            usage = tokens,
            costMicros = cost ?: 0,
            priced = cost != null,
            status = call.status,
            outcome = call.outcome,
            startedAt = call.started,
            firstByteMillis = call.firstByte?.let { (it - call.started).inWholeMilliseconds },
            durationMillis = (ended - call.started).inWholeMilliseconds,
        )
        // Best-effort and never cancelled halfway: the ledger keeps a call the agent abandoned.
        withContext(NonCancellable) {
            try {
                usage.record(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The ledger being down must not fail a call already answered.
            }
            try {
                observer.observe(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Same for traces.
            }
        }
    }

    /** What is known of a call as it goes. */
    private class Call(val caller: Caller, val protocol: WireProtocol, val sessionId: String?, val started: Instant, val model: String) {
        var provider: InferenceProvider? = null
        var accountId: Uuid? = null
        var status: Int = 0
        var outcome: String = "ok"
        var firstByte: Instant? = null
        var usage: TokenUsage? = null
    }

    /** A non-streamed body, kept (up to [MAX_BODY]) only to read its usage at the end. */
    private class Accumulator {
        private var bytes = ByteArray(0)
        private var size = 0
        private var overflow = false

        fun add(chunk: ByteArray, count: Int) {
            if (overflow) return
            if (size + count > MAX_BODY) {
                overflow = true
                return
            }
            if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(size + count, bytes.size * 2, 8 * 1024))
            chunk.copyInto(bytes, size, 0, count)
            size += count
        }

        fun bytes(): ByteArray? = if (overflow) null else bytes.copyOf(size)
    }

    companion object {
        /** Tries per call when shared accounts refuse. */
        const val MAX_ATTEMPTS = 3

        private const val CHUNK = 16 * 1024
        private const val MAX_BODY = 8 * 1024 * 1024
        private val RETRYABLE = setOf(401, 429)
        private val FORWARDED_PREFIXES = listOf("anthropic-ratelimit-", "x-ratelimit-", "openai-", "x-codex-")
        private val FORWARDED = setOf("content-type", "retry-after", "request-id", "x-request-id")

        /** The answer headers an agent may see: content type, request ids, rate limits. */
        fun forwarded(headers: Map<String, String>): List<Pair<String, String>> =
            headers.filter { (name, _) -> name in FORWARDED || FORWARDED_PREFIXES.any { name.startsWith(it) } }.toList()
    }
}
