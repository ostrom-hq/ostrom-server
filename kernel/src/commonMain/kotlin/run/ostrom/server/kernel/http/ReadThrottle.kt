// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.http

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a throttled call is: a read that answers at once, or a wait that may hold its call for up to 30 s. */
enum class ReadKind {
    /** A read answered at once ([throttledReads]). */
    READ,

    /** A long wait, a gate's answer ([throttledWaits]): counted at once apart from the reads. */
    WAIT,
}

/**
 * How much one subject (a person, a device, an agent, a service) may read of the costly routes ([throttledReads],
 * [throttledWaits]).
 *
 * @property perMinute Calls per minute, reads and waits together (`OSTROM_READS_PER_MINUTE`).
 * @property inFlight Reads at once (`OSTROM_READS_IN_FLIGHT`).
 * @property waitsInFlight Waits at once (`OSTROM_READ_WAITS_IN_FLIGHT`): an agent waits on several gates, each wait
 *   up to 30 s, without taking the reads' places.
 */
data class ReadLimits(val perMinute: Int, val inFlight: Int, val waitsInFlight: Int) {
    /**
     * How many calls of [kind] a subject holds at once.
     *
     * @param kind A read or a wait.
     *
     * @return The limit.
     */
    fun atOnce(kind: ReadKind): Int = if (kind == ReadKind.WAIT) waitsInFlight else inFlight

    /** The defaults: a console refreshing several panes, or agents waiting on many gates, stay far below. */
    companion object {
        /** Calls per minute by default: ten a second, kept up for a minute. */
        const val DEFAULT_PER_MINUTE: Int = 600

        /** Reads at once by default. */
        const val DEFAULT_IN_FLIGHT: Int = 16

        /** Waits at once by default: two hundred hooks of one agent waiting together still pass. */
        const val DEFAULT_WAITS_IN_FLIGHT: Int = 256
    }
}

/**
 * Bounds the costly reads of each subject (AUDIT-SECU-LECTURES M1): [ReadLimits.perMinute] calls in a fixed window
 * of a minute, [ReadLimits.inFlight] reads and [ReadLimits.waitsInFlight] waits at once. Kept in memory, like the
 * sign-in limiter: each replica counts on its own, enough against a loop or a flood without a database write per
 * read. Memory is bounded: past [maxKeys] subjects, those idle since their window ended are dropped, then the oldest
 * ones.
 *
 * @param clock The clock.
 * @param limits The limits.
 * @param maxKeys The most subjects counted at once ([MAX_SUBJECTS] in the server).
 */
class ReadThrottle(private val clock: Clock, private val limits: ReadLimits, private val maxKeys: Int) {
    private class Usage(var start: Instant, var count: Int, val atOnce: IntArray)

    private val mutex = Mutex()
    private val usage = LinkedHashMap<String, Usage>()

    /**
     * Takes a slot for one call of [key], unless the subject is over a limit.
     *
     * @param key The subject.
     * @param kind A read or a wait.
     *
     * @return Null when the call may go on (give the slot back with [release]); else how long to wait before trying
     *   again.
     */
    suspend fun acquire(key: String, kind: ReadKind): Duration? = mutex.withLock {
        val now = clock.now()
        val current = usage[key] ?: Usage(now, 0, IntArray(ReadKind.entries.size)).also { added ->
            if (usage.size >= maxKeys) evict(now)
            usage[key] = added
        }
        if (now - current.start >= WINDOW) {
            current.start = now
            current.count = 0
        }
        when {
            current.atOnce[kind.ordinal] >= limits.atOnce(kind) -> BUSY_RETRY
            current.count >= limits.perMinute -> WINDOW - (now - current.start)
            else -> {
                current.count++
                current.atOnce[kind.ordinal]++
                null
            }
        }
    }

    /**
     * Gives back the slot of a call of [key] that ended.
     *
     * @param key The subject.
     * @param kind What the call was, as it was acquired.
     */
    suspend fun release(key: String, kind: ReadKind) {
        mutex.withLock { usage[key]?.let { it.atOnce[kind.ordinal]-- } }
    }

    private fun evict(now: Instant) {
        usage.entries.removeAll { now - it.value.start >= WINDOW && it.value.atOnce.sum() == 0 }
        while (usage.size >= maxKeys) usage.remove(usage.keys.first())
    }

    /** The window and the most subjects. */
    companion object {
        /** The most subjects the server counts at once. */
        const val MAX_SUBJECTS: Int = 100_000

        private val WINDOW: Duration = 1.minutes
        private val BUSY_RETRY: Duration = 1.seconds
    }
}

/** A subject over its read limits: 429, with `Retry-After`. */
class ReadLimitedException : OstromException(ErrorKind.RATE_LIMITED, "Too many reads. Wait a moment and retry.")

/**
 * Routes whose reads cost the database enough to be bounded per subject by [throttle] (AUDIT-SECU-LECTURES M1). Put
 * inside `authenticated`: the subject is the call's principal; a call without one is not counted.
 *
 * @param throttle The limiter, one per server (Koin).
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.throttledReads(throttle: ReadThrottle, build: Route.() -> Unit): Route =
    throttled(throttle, ReadKind.READ, build)

/**
 * Like [throttledReads], for routes that may wait (a gate's answer, up to 30 s): their calls count per minute with the
 * reads, and at once apart from them, up to [ReadLimits.waitsInFlight].
 *
 * @param throttle The limiter, one per server (Koin).
 * @param build The routes.
 *
 * @return The route holding them.
 */
fun Route.throttledWaits(throttle: ReadThrottle, build: Route.() -> Unit): Route =
    throttled(throttle, ReadKind.WAIT, build)

private fun Route.throttled(throttle: ReadThrottle, kind: ReadKind, build: Route.() -> Unit): Route {
    val throttled = createChild(ThrottledSelector(kind))
    throttled.install(RouteReadThrottle) {
        this.throttle = throttle
        this.kind = kind
    }
    throttled.build()
    return throttled
}

/** The subject a principal's reads are counted for: one budget per person, device, agent or service. */
internal fun AuthenticatedPrincipal.readKey(): String = "${kind.name}:$subjectId"

/** Configuration of [RouteReadThrottle]. */
private class RouteReadThrottleConfig {
    lateinit var throttle: ReadThrottle
    lateinit var kind: ReadKind
}

/** Counts each call of its routes for its subject, and gives the slot back when the call ends, failed or not. */
private val RouteReadThrottle = createRouteScopedPlugin("RouteReadThrottle", ::RouteReadThrottleConfig) {
    val throttle = pluginConfig.throttle
    val kind = pluginConfig.kind
    on(AroundCall) { call, proceed ->
        val key = call.principal<AuthenticatedPrincipal>()?.readKey()
        if (key == null) {
            proceed()
        } else {
            val wait = throttle.acquire(key, kind)
            if (wait != null) {
                call.response.header(HttpHeaders.RetryAfter, (wait.inWholeSeconds + 1).toString())
                throw ReadLimitedException()
            }
            try {
                proceed()
            } finally {
                throttle.release(key, kind)
            }
        }
    }
}

/** Matches like its parent; parenthesized so that route templates leave it out. */
private class ThrottledSelector(private val kind: ReadKind) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(throttled $kind)"
}

/** The call phase, around the route's handler: after authentication, so the principal is known. */
private object AroundCall : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) { handler(call) { proceed() } }
    }
}
