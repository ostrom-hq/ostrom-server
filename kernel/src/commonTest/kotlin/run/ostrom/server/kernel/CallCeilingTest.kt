// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.CallCeilingSettings
import run.ostrom.server.kernel.http.CallKind
import run.ostrom.server.kernel.http.CallsInProgress
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.http.heldCalls
import run.ostrom.server.kernel.http.openConnections
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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
    fun theCeilingIsComputedAgainWhenTheLimitChangesAndHeldCallsGetHalf() {
        var limit: Long? = 512 * mib
        val time = TestTimeSource()
        val calls = CallsInProgress(CallCeilingSettings(), { limit }, time)
        assertEquals(150, calls.ceiling(CallKind.SERVED))
        assertEquals(75, calls.ceiling(CallKind.HELD), "both kinds full stay near the 200 calls measured at 377 MiB")
        // With 150, the three kinds full peaked at 495 MiB under 512 MiB (CHARGE 41668930337).
        assertEquals(100, calls.ceiling(CallKind.OPEN))
        limit = 1024 * mib
        time += 5.seconds
        assertEquals(300, calls.ceiling(CallKind.SERVED))
        assertEquals(150, calls.ceiling(CallKind.HELD))
        assertEquals(200, calls.ceiling(CallKind.OPEN))
        limit = 32 * mib
        time += 5.seconds
        assertEquals(16, calls.ceiling(CallKind.OPEN), "never under 16")
        assertEquals(Int.MAX_VALUE, calls.ceiling(CallKind.FREE))
    }

    @Test
    fun theLimitIsReadAgainEveryFiveSecondsAndAFailedReadKeepsTheLast() {
        var reads = 0
        var limit: Long? = 512 * mib
        val time = TestTimeSource()
        val calls = CallsInProgress(CallCeilingSettings(), { reads++; limit }, time)
        repeat(3) { assertEquals(150, calls.ceiling(CallKind.SERVED)) }
        assertEquals(1, reads, "not a read per call")
        limit = 1024 * mib
        time += 4.seconds
        assertEquals(150, calls.ceiling(CallKind.SERVED), "the last read still holds")
        time += 1.seconds
        assertEquals(300, calls.ceiling(CallKind.SERVED))
        limit = null
        time += 5.seconds
        assertEquals(300, calls.ceiling(CallKind.SERVED), "a failed read never makes the ceiling jump")
        assertEquals(3, reads)
    }

    @Test
    fun withoutALimitTheCeilingsAreTheDefault() {
        val calls = CallsInProgress(CallCeilingSettings(), { null })
        assertEquals(CallsInProgress.DEFAULT_CEILING, calls.ceiling(CallKind.SERVED))
        assertEquals(CallsInProgress.DEFAULT_CEILING / 2, calls.ceiling(CallKind.HELD))
        assertEquals(170, calls.ceiling(CallKind.OPEN), "two thirds of the ordinary calls, as under a limit")
    }

    @Test
    fun theHeldCeilingFollowsAnOrdinaryCeilingSetByTheInstance() {
        assertEquals(25, CallsInProgress(CallCeilingSettings(served = 50), { 1024 * mib }).ceiling(CallKind.HELD))
        assertEquals(1, CallsInProgress(CallCeilingSettings(served = 1), { null }).ceiling(CallKind.HELD), "at least 1")
    }

    @Test
    fun aCeilingSetByTheInstanceWinsOverTheLimit() {
        val calls = CallsInProgress(CallCeilingSettings(served = 2, held = 3, open = 4), { 512 * mib })
        assertEquals(2, calls.ceiling(CallKind.SERVED))
        assertEquals(3, calls.ceiling(CallKind.HELD))
        assertEquals(4, calls.ceiling(CallKind.OPEN))
    }

    @Test
    fun eachKindCountsUnderItsOwnCeiling() = runTest {
        val calls = CallsInProgress(CallCeilingSettings(served = 1, held = 1, open = 1, queueWait = ZERO), { null })
        val served = assertNotNull(calls.enter(CallKind.SERVED))
        assertNull(calls.enter(CallKind.SERVED), "the served place is taken")
        val held = assertNotNull(calls.enter(CallKind.HELD), "a held call does not count with the served ones")
        assertNull(calls.enter(CallKind.HELD))
        val open = assertNotNull(calls.enter(CallKind.OPEN), "an open connection takes no wait's place")
        assertNull(calls.enter(CallKind.OPEN))
        open.leave()
        assertEquals(0, calls.inProgress(CallKind.OPEN))
        repeat(3) { assertNotNull(calls.enter(CallKind.FREE), "never counted").leave() }
        assertEquals(1, calls.inProgress(CallKind.SERVED), "a refusal takes no place")
        assertEquals(0, calls.inProgress(CallKind.FREE))
        served.leave()
        held.leave()
        assertEquals(0, calls.inProgress(CallKind.HELD))
        assertNotNull(calls.enter(CallKind.SERVED), "a place given back is taken again")
    }

    @Test
    fun pastTheCeilingACallWaitsInLineForAPlaceThenIsRefused() = runTest {
        val calls = CallsInProgress(CallCeilingSettings(served = 1, queueWait = 2.seconds), { null })
        val first = assertNotNull(calls.enter(CallKind.SERVED))
        val waiting = async { calls.enter(CallKind.SERVED) }
        advanceTimeBy(1.seconds)
        assertFalse(waiting.isCompleted, "it waits for a place")
        first.leave()
        val second = assertNotNull(waiting.await(), "the place given back goes to the call in line")
        val start = testScheduler.currentTime
        assertNull(calls.enter(CallKind.SERVED), "no place within the wait")
        assertEquals(2_000, testScheduler.currentTime - start, "refused once the wait is over")
        second.leave()
    }

    @Test
    fun aFullLineRefusesTheNextCallAtOnce() = runTest {
        val calls = CallsInProgress(CallCeilingSettings(served = 1, queueWait = 2.seconds), { null })
        val first = assertNotNull(calls.enter(CallKind.SERVED))
        val waiting = async { calls.enter(CallKind.SERVED) }
        advanceTimeBy(1.seconds)
        val start = testScheduler.currentTime
        assertNull(calls.enter(CallKind.SERVED), "as many calls wait as the ceiling: no more join the line")
        assertEquals(0, testScheduler.currentTime - start, "refused at once, without waiting")
        first.leave()
        assertNotNull(waiting.await()).leave()
    }

    @Test
    fun aNewLimitAdmitsCallsUnderItsNewCeiling() = runTest {
        var limit = 32 * mib
        val time = TestTimeSource()
        val calls = CallsInProgress(CallCeilingSettings(queueWait = ZERO), { limit }, time)
        repeat(16) { assertNotNull(calls.enter(CallKind.SERVED)) }
        assertNull(calls.enter(CallKind.SERVED), "16 under 32 MiB")
        limit = 1024 * mib
        time += 5.seconds
        assertNotNull(calls.enter(CallKind.SERVED), "300 under 1 GiB")
    }

    /** Routes that hold their call until [release] completes, and say when they are in. */
    private class Probe(private val release: Deferred<Unit>) : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}
        val servedIn = CompletableDeferred<Unit>()
        val heldIn = CompletableDeferred<Unit>()
        val openIn = CompletableDeferred<Unit>()

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
            // A route that speaks another API answers its own refusal past the same ceiling (the gateway's).
            val ownRefusal: suspend (ApplicationCall) -> Unit = { call ->
                call.respondText("own refusal", status = HttpStatusCode.TooManyRequests)
            }
            route.heldCalls(refuse = ownRefusal) {
                get("/probe/held-own") { call.respondText("held own") }
            }
            route.openConnections {
                get("/probe/open") {
                    openIn.complete(Unit)
                    release.await()
                    call.respondText("open")
                }
            }
        }
    }

    private fun ceilingTest(
        wait: Duration = ZERO,
        block: suspend ApplicationTestBuilder.(Probe, CompletableDeferred<Unit>) -> Unit,
    ) = testApplication {
        val release = CompletableDeferred<Unit>()
        val probe = Probe(release)
        val calls = CallsInProgress(CallCeilingSettings(served = 1, held = 1, open = 1, queueWait = wait), { null })
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
    fun aStreamRefusedPastTheCeilingIsTold503InJsonNot406() = ceilingTest { probe, release ->
        // CHARGE 41672759077: the live stream asks `Accept: text/event-stream`; the refusal, an ApiError left to
        // content negotiation, found no converter for that type and went out as a 406 with an empty body.
        coroutineScope {
            val first = async { client.get("/probe/served").bodyAsText() }
            probe.servedIn.await()
            val refused = client.get("/probe/quick") { header(HttpHeaders.Accept, "text/event-stream") }
            assertBusy(refused, refused.bodyAsText())
            assertEquals(ContentType.Application.Json, refused.contentType()?.withoutParameters())
            release.complete(Unit)
            assertEquals("served", first.await())
        }
    }

    @Test
    fun aCallInLineIsServedOnceAPlaceIsGivenBack() = ceilingTest(wait = 10.seconds) { probe, release ->
        coroutineScope {
            val first = async { client.get("/probe/served").bodyAsText() }
            probe.servedIn.await()
            val waiting = async { client.get("/probe/quick").bodyAsText() }
            delay(200.milliseconds)
            assertFalse(waiting.isCompleted, "it waits in line")
            release.complete(Unit)
            assertEquals("served", first.await())
            assertEquals("quick", waiting.await(), "served, not refused")
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
    fun aHeldRouteWithItsOwnRefusalAnswersItPastTheCeiling() = ceilingTest { probe, release ->
        assertEquals("held own", client.get("/probe/held-own").bodyAsText(), "under the ceiling, served")
        coroutineScope {
            val held = async { client.get("/probe/held").bodyAsText() }
            probe.heldIn.await()
            val refused = client.get("/probe/held-own")
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals("own refusal", refused.bodyAsText())
            release.complete(Unit)
            assertEquals("held", held.await())
        }
    }

    @Test
    fun anOpenConnectionHasItsOwnCeilingApartFromTheHeldCalls() = ceilingTest { probe, release ->
        coroutineScope {
            val open = async { client.get("/probe/open").bodyAsText() }
            probe.openIn.await()
            val held = async { client.get("/probe/held").bodyAsText() }
            probe.heldIn.await()
            val secondOpen = client.get("/probe/open") { header(HttpHeaders.Accept, "text/event-stream") }
            assertBusy(secondOpen, secondOpen.bodyAsText())
            release.complete(Unit)
            assertEquals("open", open.await())
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
