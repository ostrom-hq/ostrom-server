// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalAtomicApi::class)

package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How a call counts against the replica's ceilings ([CallsInProgress]). */
enum class CallKind {
    /** An ordinary call: answered in at most a few seconds. */
    SERVED,

    /** A call held while it waits (a gate or a confirmation, up to 30 s; a model or an MCP server's answer). */
    HELD,

    /** A connection open as long as its client keeps it (a terminal's WebSocket, the live usage stream). */
    OPEN,

    /** Never counted: `/health` and `/ready`, which the orchestrator must always reach. */
    FREE,
}

/**
 * The ceilings set by the instance: `OSTROM_MAX_CALLS_IN_PROGRESS`, `OSTROM_MAX_HELD_CALLS` and
 * `OSTROM_MAX_OPEN_CONNECTIONS`, each null to derive it, and how long a call waits for a place
 * (`OSTROM_CALL_QUEUE_SECONDS`).
 *
 * @property served The most ordinary calls at once.
 * @property held The most held calls at once.
 * @property open The most open connections at once.
 * @property queueWait How long a call past the ceiling waits for a place before its 503.
 */
data class CallCeilingSettings(
    val served: Int? = null,
    val held: Int? = null,
    val open: Int? = null,
    val queueWait: Duration = DEFAULT_QUEUE_WAIT,
) {
    /** The defaults. */
    companion object {
        /** How long a burst waits for its place. */
        val DEFAULT_QUEUE_WAIT: Duration = 5.seconds
    }
}

/** A place taken by [CallsInProgress.enter], given back once by [leave]. */
fun interface CallPlace {
    /** Gives the place back. */
    fun leave()
}

/**
 * The calls this replica runs at once, per [CallKind], each under its ceiling; past it, a call waits in line for
 * [CallCeilingSettings.queueWait], then is refused, and at once when as many calls as the ceiling already wait. A call
 * costs memory while it runs, and up to 1 MiB more past about 100 at once: the reads, already bounded, held 500 at
 * once under 512 MiB (CHARGE 41647341533), while 500 approvals, writes bounded by nothing, OOMKilled the server
 * (41644506470). Unset, the ceilings follow the container's limit, read again every 5 s: 150 ordinary calls under
 * 512 MiB, in proportion above and below, at least 16, and 256 without a limit; half the ordinary ceiling of held
 * calls, so that both kinds full stay near the 200 calls measured at 377 MiB; and as many open connections as
 * ordinary calls, an idle connection costing little.
 *
 * @param settings The ceilings the instance sets.
 * @param limitBytes Reads the container's memory limit; null without one.
 * @param time Measures when the limit was last read.
 */
class CallsInProgress(
    private val settings: CallCeilingSettings,
    private val limitBytes: () -> Long?,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val served = Places()
    private val held = Places()
    private val open = Places()
    private val limit = AtomicReference(LimitReading(null, null))

    /**
     * The ceiling of [kind] now.
     *
     * @param kind The kind of call.
     *
     * @return The most calls of [kind] at once.
     */
    fun ceiling(kind: CallKind): Int = when (kind) {
        CallKind.SERVED -> settings.served ?: ceilingUnder(currentLimit())
        CallKind.HELD -> settings.held ?: (ceilingUnder(currentLimit()) / HELD_SHARE).coerceAtLeast(1)
        CallKind.OPEN -> ceiling(CallKind.HELD)
        CallKind.FREE -> Int.MAX_VALUE
    }

    /**
     * The container's limit, read again at most every [LIMIT_EVERY]: a read on every call would cost the file's system
     * calls to each request. A read that fails keeps the last limit, so that a ceiling never jumps on a passing error.
     */
    private fun currentLimit(): Long? {
        val last = limit.load()
        if (last.at != null && last.at.elapsedNow() < LIMIT_EVERY) return last.bytes
        val bytes = limitBytes() ?: last.bytes
        limit.store(LimitReading(bytes, time.markNow()))
        return bytes
    }

    /**
     * Takes a place for a call of [kind], waiting in line for one up to [CallCeilingSettings.queueWait].
     *
     * @param kind The kind of call.
     *
     * @return The place, to give back once the call ends; null when none came in time.
     */
    suspend fun enter(kind: CallKind): CallPlace? {
        val places = placesOf(kind) ?: return NO_PLACE
        return places.enter(ceiling(kind), settings.queueWait)
    }

    /**
     * The calls of [kind] running now.
     *
     * @param kind The kind of call.
     *
     * @return Their number; 0 for [CallKind.FREE], never counted.
     */
    fun inProgress(kind: CallKind): Long = placesOf(kind)?.let { it.running.load() } ?: 0

    private fun placesOf(kind: CallKind): Places? = when (kind) {
        CallKind.SERVED -> served
        CallKind.HELD -> held
        CallKind.OPEN -> held
        CallKind.FREE -> null
    }

    /**
     * The places of one kind: a semaphore as wide as the ceiling, chosen under a lock so that the first calls of a
     * burst all take the same one. A new ceiling (the container's limit changed) starts a new semaphore; the calls in
     * the old one give their places back to it, so that for a moment both count.
     */
    private class Places {
        val running = AtomicLong(0)
        private val waiting = AtomicLong(0)
        private val choosing = Mutex()

        // No ceiling is 0: the first call starts the first real line.
        private var line = Line(0, Semaphore(1))

        /** Past [ceiling] calls already in line, a call is refused at once: the line holds memory too. */
        suspend fun enter(ceiling: Int, wait: Duration): CallPlace? {
            val semaphore = lineOf(ceiling).semaphore
            val taken = semaphore.tryAcquire() || waitInLine(semaphore, ceiling, wait)
            return if (taken) {
                running.incrementAndFetch()
                CallPlace {
                    running.decrementAndFetch()
                    semaphore.release()
                }
            } else {
                null
            }
        }

        private suspend fun waitInLine(semaphore: Semaphore, ceiling: Int, wait: Duration): Boolean = try {
            waiting.incrementAndFetch() <= ceiling && withTimeoutOrNull(wait) { semaphore.acquire() } != null
        } finally {
            waiting.decrementAndFetch()
        }

        private suspend fun lineOf(ceiling: Int): Line = choosing.withLock {
            line.takeIf { it.ceiling == ceiling } ?: Line(ceiling, Semaphore(ceiling)).also { line = it }
        }
    }

    private class Line(val ceiling: Int, val semaphore: Semaphore)

    private class LimitReading(val bytes: Long?, val at: TimeMark?)

    /** The ceilings a limit gives. */
    companion object {
        /** Without a container limit. */
        const val DEFAULT_CEILING = 256

        private const val MIB = 1024L * 1024
        private const val CALLS_UNDER_REFERENCE = 150L
        private const val REFERENCE_MIB = 512L
        private const val MIN_CEILING = 16L
        private const val HELD_SHARE = 2
        private val LIMIT_EVERY = 5.seconds
        private val NO_PLACE = CallPlace {}

        /**
         * The ceiling under [limitBytes]: 150 calls per 512 MiB, at least 16.
         *
         * @param limitBytes The container's memory limit, null without one.
         *
         * @return The ceiling.
         */
        fun ceilingUnder(limitBytes: Long?): Int = limitBytes?.let {
            (it / MIB * CALLS_UNDER_REFERENCE / REFERENCE_MIB).coerceIn(MIN_CEILING, Int.MAX_VALUE.toLong()).toInt()
        } ?: DEFAULT_CEILING
    }
}

/** A replica running as many calls as it holds: 503, with `Retry-After`. */
class ServerBusyException : OstromException(
    ErrorKind.UNAVAILABLE,
    "This server is answering as many calls as it can hold. Retry in a moment.",
)

/** The server's counters, put on the application by [configureHttp] before the routes are built. */
internal val callsInProgressKey = AttributeKey<CallsInProgress>("ostrom.calls.in.progress")

/** Configuration of [CallCeiling]. */
class CallCeilingConfig {
    /** How the calls of the routes it is installed on count. */
    var kind: CallKind = CallKind.SERVED
}

/**
 * Counts each call against the replica's ceiling for its kind, before the body is read; past it, the call waits in
 * line for a place, then is refused: 503 `unavailable` with `Retry-After: 1`. Installed on the routing root by
 * [configureHttp] for [CallKind.SERVED]; [heldCalls] and [uncountedCalls] install it again on their routes, and the
 * closest installation counts the call, once. Routes built without [configureHttp] (a module's own test) are not
 * counted.
 */
val CallCeiling = createRouteScopedPlugin("CallCeiling", ::CallCeilingConfig) {
    val calls = application.attributes.getOrNull(callsInProgressKey)
    val kind = pluginConfig.kind
    on(AroundEveryPlugin) { call, proceed ->
        if (calls == null) {
            proceed()
        } else {
            val place = calls.enter(kind)
            if (place == null) {
                call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
                throw ServerBusyException()
            }
            try {
                proceed()
            } finally {
                place.leave()
            }
        }
    }
}

private const val RETRY_AFTER_SECONDS = "1"

/**
 * Routes whose calls stay open (a wait, a WebSocket, a stream): counted under [CallKind.HELD], apart from the
 * ordinary calls, so that they neither take the ordinary calls' places nor escape a ceiling.
 *
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.heldCalls(build: Route.() -> Unit): Route = counted(CallKind.HELD, build)

/**
 * Routes whose connections stay open as long as their client keeps them (a terminal's WebSocket, the live usage
 * stream): counted under [CallKind.OPEN], apart from the calls that wait, so that permanent connections never take
 * the places of the waits and the model calls, nor escape a ceiling.
 *
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.openConnections(build: Route.() -> Unit): Route = counted(CallKind.OPEN, build)

/**
 * Routes never counted: `/health` and `/ready`.
 *
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.uncountedCalls(build: Route.() -> Unit): Route = counted(CallKind.FREE, build)

private fun Route.counted(kind: CallKind, build: Route.() -> Unit): Route {
    val counted = createChild(CallKindSelector(kind))
    counted.install(CallCeiling) { this.kind = kind }
    counted.build()
    return counted
}

/** Matches like its parent; parenthesized so that route templates leave it out. */
private class CallKindSelector(private val kind: CallKind) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(calls $kind)"
}

/** The plugins phase, around everything the route does: authentication, the body, the handler. */
private object AroundEveryPlugin : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(call) { proceed() } }
    }
}
