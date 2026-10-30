package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.metrics.Buckets
import dev.ostrom.core.telemetry.metrics.GaugeReading
import dev.ostrom.core.telemetry.metrics.Meter
import dev.ostrom.core.telemetry.metrics.increment
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration

/**
 * The server's own metrics, created once at start and shared through Koin. Every name and attribute here is listed in
 * `ostrom-infra/observability/catalog.py`, which the dashboards and alerts are checked against: rename one in both.
 * Attributes are bounded values (an enum, a route template), never an identifier.
 *
 * @param meter Where the instruments are created.
 */
@OptIn(ExperimentalAtomicApi::class)
class OstromMetrics(private val meter: Meter) {
    private val inFlight = AtomicLong(0)
    private val requestDuration = meter.histogram(
        "http.server.request.duration",
        "s",
        "Duration of HTTP requests served.",
        Buckets.LATENCY_SECONDS,
    )
    private val gatesRequested = meter.counter("ostrom.gates.requested", "{gate}", "Gates opened by an agent.")
    private val gatesAnswered = meter.counter("ostrom.gates.answered", "{gate}", "Gates answered by an approver.")
    private val timeToAnswer = meter.histogram(
        "ostrom.gates.time_to_answer",
        "s",
        "Time from a gate opening to its answer.",
        Buckets.HUMAN_SECONDS,
    )
    private val sessionsStarted = meter.counter("ostrom.sessions.started", "{session}", "Agent sessions started.")
    private val inferenceCost = meter.counter("ostrom.inference.cost_usd", "{USD}", "Inference cost, in US dollars.")
    private val inferenceTokens = meter.counter("ostrom.inference.tokens", "{token}", "Tokens sent and received.")
    private val inferenceDuration = meter.histogram(
        "ostrom.inference.duration",
        "s",
        "Duration of model calls.",
        Buckets.LATENCY_SECONDS,
    )

    init {
        meter.gauge("http.server.active_requests", "{request}", "HTTP requests being served.") {
            listOf(GaugeReading(inFlight.load().toDouble()))
        }
    }

    /** Counts a request that started; [requestServed] counts it out. */
    fun requestStarted() {
        inFlight.incrementAndFetch()
    }

    /**
     * Records one answered request.
     *
     * @param method The HTTP method.
     * @param route The route template (`/v1/gates/{id}`), never the concrete path; `unmatched` when no route matched.
     * @param status The response status code.
     * @param duration How long the request took.
     */
    fun requestServed(method: String, route: String, status: Int, duration: Duration) {
        val attributes = Attributes.Builder()
            .put("http.request.method", method)
            .put("http.route", route)
            .put("http.response.status_code", status.toLong())
            .build()
        inFlight.decrementAndFetch()
        requestDuration.record(duration.inWholeMicroseconds / MICROS_PER_SECOND, attributes)
    }

    /**
     * Counts a gate opened by an agent.
     *
     * @param kind The gate kind's wire name (`push_main`, `merge`, `deploy`, `custom`, `other`).
     */
    fun gateRequested(kind: String) {
        gatesRequested.increment(Attributes.of("kind" to kind))
    }

    /**
     * Counts an answer and records how long it took.
     *
     * @param answer `yes` or `no`.
     * @param channel Where the approver answered (`web`, `app`, `slack`, `cli`).
     * @param waited The time between the gate opening and the answer.
     */
    fun gateAnswered(answer: String, channel: String, waited: Duration) {
        gatesAnswered.increment(Attributes.of("answer" to answer, "channel" to channel))
        timeToAnswer.record(waited.inWholeMilliseconds / MILLIS_PER_SECOND, Attributes.of("answer" to answer))
    }

    /**
     * Counts an agent session.
     *
     * @param agent The agent's wire name (`claude`, `codex`, `other`).
     * @param where `local` or `cloud`.
     */
    fun sessionStarted(agent: String, where: String) {
        sessionsStarted.increment(Attributes.of("agent" to agent, "where" to where))
    }

    /**
     * Records the cost, tokens and duration of a model call (its span goes to Langfuse separately).
     *
     * @param call The call.
     * @param duration How long it took.
     */
    fun modelCalled(call: ModelCall, duration: Duration) {
        val source = Attributes.of("source" to call.source.wire, "provider" to call.provider)
        call.costUsd?.takeIf { it.isFinite() && it >= 0.0 }?.let { inferenceCost.add(it, source) }
        inferenceTokens.add(call.inputTokens.toDouble(), source + Attributes.of("type" to "input"))
        inferenceTokens.add(call.outputTokens.toDouble(), source + Attributes.of("type" to "output"))
        inferenceDuration.record(duration.inWholeMilliseconds / MILLIS_PER_SECOND, source)
    }

    /**
     * Registers the number of gates waiting for an answer, read at each export. Every replica reports the same
     * org-wide count: dashboards take the max, not the sum.
     *
     * @param read Returns the last known count; must be fast (a cached value, not a query).
     */
    fun observeGatesPending(read: () -> Long) {
        meter.gauge("ostrom.gates.pending", "{gate}", "Gates waiting for an answer.") {
            listOf(GaugeReading(read().toDouble()))
        }
    }

    /**
     * Registers the running agent sessions and the cloud capacity, read at each export.
     *
     * @param active Returns the running sessions by place (`local`, `cloud`); must be fast.
     * @param cloudCapacity The most cloud sessions allowed at once (`OSTROM_CLOUD_MAX_CONCURRENT`).
     */
    fun observeSessions(active: () -> Map<String, Long>, cloudCapacity: Long) {
        meter.gauge("ostrom.sessions.active", "{session}", "Agent sessions running.") {
            active().map { (where, count) -> GaugeReading(count.toDouble(), Attributes.of("where" to where)) }
        }
        meter.gauge("ostrom.sessions.cloud_capacity", "{session}", "Cloud sessions allowed at once.") {
            listOf(GaugeReading(cloudCapacity.toDouble()))
        }
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000.0
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
