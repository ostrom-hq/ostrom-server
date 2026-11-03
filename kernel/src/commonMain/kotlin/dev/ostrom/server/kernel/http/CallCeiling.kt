// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalAtomicApi::class)

package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** How a call counts against the replica's ceilings ([CallsInProgress]). */
enum class CallKind {
    /** An ordinary call: answered in at most a few seconds. */
    SERVED,

    /** A call that stays open (a wait up to 30 s, a WebSocket, a stream): counted apart, under its own ceiling. */
    HELD,

    /** Never counted: `/health` and `/ready`, which the orchestrator must always reach. */
    FREE,
}

/**
 * The ceilings set by the instance: `OSTROM_MAX_CALLS_IN_PROGRESS` and `OSTROM_MAX_HELD_CALLS`, each null to derive it
 * from the container's memory limit.
 *
 * @property served The most ordinary calls at once.
 * @property held The most held calls at once.
 */
data class CallCeilingSettings(val served: Int? = null, val held: Int? = null)

/**
 * The calls this replica runs at once, per [CallKind], each under its ceiling. A call costs memory while it runs, and
 * up to 1 MiB more past about 100 at once: 500 approvals at once OOMKilled the server under 512 MiB, 200 peaked at
 * 377 MiB (CHARGE 41644506470). Unset, a ceiling follows the container's limit, read at every call so that a new limit
 * applies at once: 150 calls under 512 MiB, in proportion above and below, at least 16, and 256 without a limit.
 *
 * @param settings The ceilings the instance sets.
 * @param limitBytes Reads the container's memory limit; null without one.
 */
class CallsInProgress(private val settings: CallCeilingSettings, private val limitBytes: () -> Long?) {
    private val served = AtomicLong(0)
    private val held = AtomicLong(0)

    /**
     * The ceiling of [kind] now.
     *
     * @param kind The kind of call.
     *
     * @return The most calls of [kind] at once.
     */
    fun ceiling(kind: CallKind): Int = when (kind) {
        CallKind.SERVED -> settings.served ?: ceilingUnder(limitBytes())
        CallKind.HELD -> settings.held ?: ceilingUnder(limitBytes())
        CallKind.FREE -> Int.MAX_VALUE
    }

    /**
     * Takes a place for a call of [kind], without waiting.
     *
     * @param kind The kind of call.
     *
     * @return True when admitted; the caller then calls [leave] exactly once.
     */
    fun tryEnter(kind: CallKind): Boolean = counterOf(kind)?.incrementAndFetch().let { true }

    /**
     * Gives back the place of a call admitted by [tryEnter].
     *
     * @param kind The kind of call.
     */
    fun leave(kind: CallKind) {
        counterOf(kind)?.decrementAndFetch()
    }

    /**
     * The calls of [kind] running now.
     *
     * @param kind The kind of call.
     *
     * @return Their number; 0 for [CallKind.FREE], never counted.
     */
    fun inProgress(kind: CallKind): Long = counterOf(kind)?.load() ?: 0

    private fun counterOf(kind: CallKind): AtomicLong? = when (kind) {
        CallKind.SERVED -> served
        CallKind.HELD -> held
        CallKind.FREE -> null
    }

    /** The ceilings a limit gives. */
    companion object {
        /** Without a container limit. */
        const val DEFAULT_CEILING = 256

        private const val MIB = 1024L * 1024
        private const val CALLS_UNDER_REFERENCE = 150L
        private const val REFERENCE_MIB = 512L
        private const val MIN_CEILING = 16L

        /**
         * The ceiling under [limitBytes]: 150 calls per 512 MiB, at least 16.
         *
         * @param limitBytes The container's memory limit, null without one.
         *
         * @return The ceiling.
         */
        fun ceilingUnder(limitBytes: Long?): Int = DEFAULT_CEILING
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
 * Counts each call against the replica's ceiling for its kind, and refuses it at once past it: 503 `unavailable` with
 * `Retry-After: 1`, before the body is read. Installed on the routing root by [configureHttp] for [CallKind.SERVED];
 * [heldCalls] and [uncountedCalls] install it again on their routes, and the closest installation counts the call,
 * once. Routes built without [configureHttp] (a module's own test) are not counted.
 */
val CallCeiling = createRouteScopedPlugin("CallCeiling", ::CallCeilingConfig) {
    val calls = application.attributes.getOrNull(callsInProgressKey)
    val kind = pluginConfig.kind
    on(AroundEveryPlugin) { call, proceed ->
        if (calls == null) {
            proceed()
        } else if (!calls.tryEnter(kind)) {
            call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
            throw ServerBusyException()
        } else {
            try {
                proceed()
            } finally {
                calls.leave(kind)
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
