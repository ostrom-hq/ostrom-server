// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.CallCeilingSettings
import dev.ostrom.server.kernel.http.CallKind
import dev.ostrom.server.kernel.http.CallsInProgress
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.http.heldCalls
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** P1 memory: a replica runs at most so many calls at once, from its container's limit, and refuses the next ones. */
class CallCeilingTest {
    private val mib = 1024L * 1024

    @Test
    fun theCeilingFollowsTheContainersLimit() {
        assertEquals(150, CallsInProgress.ceilingUnder(512 * mib))
        assertEquals(300, CallsInProgress.ceilingUnder(1024 * mib))
        assertEquals(75, CallsInProgress.ceilingUnder(256 * mib))
        assertEquals(16, CallsInProgress.ceilingUnder(32 * mib), "never under 16")
        assertEquals(CallsInProgress.DEFAULT_CEILING, CallsInProgress.ceilingUnder(null))
        assertEquals(Int.MAX_VALUE, CallsInProgress.ceilingUnder(Long.MAX_VALUE), "no overflow")
    }

    @Test
    fun theCeilingIsComputedAgainWhenTheLimitChanges() {
        var limit: Long? = 512 * mib
        val calls = CallsInProgress(CallCeilingSettings()) { limit }
        assertEquals(150, calls.ceiling(CallKind.SERVED))
        assertEquals(150, calls.ceiling(CallKind.HELD))
        limit = 1024 * mib
        assertEquals(300, calls.ceiling(CallKind.SERVED))
        assertEquals(300, calls.ceiling(CallKind.HELD))
        limit = null
        assertEquals(CallsInProgress.DEFAULT_CEILING, calls.ceiling(CallKind.SERVED))
        assertEquals(Int.MAX_VALUE, calls.ceiling(CallKind.FREE))
    }

    @Test
    fun aCeilingSetByTheInstanceWinsOverTheLimit() {
        val calls = CallsInProgress(CallCeilingSettings(served = 2, held = 3)) { 512 * mib }
        assertEquals(2, calls.ceiling(CallKind.SERVED))
        assertEquals(3, calls.ceiling(CallKind.HELD))
    }

    @Test
    fun eachKindCountsUnderItsOwnCeiling() {
        val calls = CallsInProgress(CallCeilingSettings(served = 1, held = 1)) { null }
        assertTrue(calls.tryEnter(CallKind.SERVED))
        assertFalse(calls.tryEnter(CallKind.SERVED), "the served place is taken")
        assertTrue(calls.tryEnter(CallKind.HELD), "a held call does not count with the served ones")
        assertFalse(calls.tryEnter(CallKind.HELD))
        repeat(3) { assertTrue(calls.tryEnter(CallKind.FREE), "never counted") }
        assertEquals(1, calls.inProgress(CallKind.SERVED), "a refusal gives its count back")
        assertEquals(0, calls.inProgress(CallKind.FREE))
        calls.leave(CallKind.SERVED)
        calls.leave(CallKind.FREE)
        assertTrue(calls.tryEnter(CallKind.SERVED), "a place given back is taken again")
    }

    /** Routes that hold their call until [release] completes, and say when they are in. */
    private class Probe(private val release: Deferred<Unit>) : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}
        val servedIn = CompletableDeferred<Unit>()
        val heldIn = CompletableDeferred<Unit>()

        override fun routes(route: Route, koin: Koin) {
            route.get("/probe/served") {
                servedIn.complete(Unit)
                release.await()
                call.respondText("served")
            }
            route.get("/probe/quick") { call.respondText("quick") }
            route.get("/probe/fail") { throw OstromException(ErrorKind.NOT_FOUND, "Not found.") }
            route.heldCalls {
                get("/probe/held") {
                    heldIn.complete(Unit)
                    release.await()
                    call.respondText("held")
                }
            }
        }
    }

    private fun ceilingTest(block: suspend ApplicationTestBuilder.(Probe, CompletableDeferred<Unit>) -> Unit) =
        testApplication {
            val release = CompletableDeferred<Unit>()
            val probe = Probe(release)
            val calls = CallsInProgress(CallCeilingSettings(served = 1, held = 1)) { null }
            val koin = koinApplication {
                modules(
                    module {
                        single<Logger> { JsonLogger(LogLevel.WARN, sink = {}) }
                        single<TokenAuthenticator> {
                            object : TokenAuthenticator {
                                override suspend fun authenticate(token: String) =
                                    AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet())
                            }
                        }
                        single { calls }
                    },
                )
            }.koin
            application { configureHttp(koin, listOf(probe)) }
            block(probe, release)
            // A call answered gives its place back just after its answer is sent.
            withTimeout(5.seconds) { while (calls.inProgress(CallKind.SERVED) > 0) delay(10.milliseconds) }
        }

    private fun assertBusy(response: HttpResponse, body: String) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertTrue("unavailable" in body, body)
    }

    @Test
    fun pastTheCeilingACallIsTold503AtOnceWithRetryAfter() = ceilingTest { probe, release ->
        coroutineScope {
            val first = async { client.get("/probe/served").bodyAsText() }
            probe.servedIn.await()
            val refused = client.get("/probe/quick")
            assertBusy(refused, refused.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.get("/health").status, "the probes are never counted")
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            release.complete(Unit)
            assertEquals("served", first.await())
        }
    }

    @Test
    fun aHeldCallDoesNotCountWithTheServedOnesAndHasItsOwnCeiling() = ceilingTest { probe, release ->
        coroutineScope {
            val served = async { client.get("/probe/served").bodyAsText() }
            probe.servedIn.await()
            val held = async { client.get("/probe/held").bodyAsText() }
            probe.heldIn.await()
            val secondHeld = client.get("/probe/held")
            assertBusy(secondHeld, secondHeld.bodyAsText())
            release.complete(Unit)
            assertEquals("served", served.await())
            assertEquals("held", held.await())
        }
    }

    @Test
    fun aCallAnsweredGivesItsPlaceBack() = ceilingTest { _, _ ->
        assertEquals("quick", client.get("/probe/quick").bodyAsText())
    }

    @Test
    fun aFailedCallGivesItsPlaceBack() = ceilingTest { _, _ ->
        assertEquals(HttpStatusCode.NotFound, client.get("/probe/fail").status)
        assertEquals("quick", client.get("/probe/quick").bodyAsText())
    }

    @Test
    fun heldRoutesOutsideTheKernelsHttpLayerAreNotCounted() = testApplication {
        routing { heldCalls { get("/held") { call.respondText("held") } } }
        repeat(2) { assertEquals("held", client.get("/held").bodyAsText()) }
    }
}
