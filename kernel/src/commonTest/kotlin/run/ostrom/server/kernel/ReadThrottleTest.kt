// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.ReadKind
import run.ostrom.server.kernel.http.ReadLimits
import run.ostrom.server.kernel.http.ReadThrottle
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.http.throttledReads
import run.ostrom.server.kernel.http.throttledWaits
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** AUDIT-SECU-LECTURES M1: the costly reads are bounded per subject, per minute and at once. */
class ReadThrottleTest {
    private var now = Instant.parse("2026-11-03T08:00:00Z")
    private val clock = object : Clock {
        override fun now() = this@ReadThrottleTest.now
    }

    @Test
    fun aSubjectOverItsCallsOfTheMinuteWaitsForTheNextWindow() = runTest {
        val throttle = ReadThrottle(clock, ReadLimits(2, 10, 10), maxKeys = 10)
        repeat(2) {
            assertNull(throttle.acquire("a", ReadKind.READ))
            throttle.release("a", ReadKind.READ)
        }
        now += 10.seconds
        assertEquals(50.seconds, throttle.acquire("a", ReadKind.READ))
        // Another subject has its own budget.
        assertNull(throttle.acquire("b", ReadKind.READ))
        now += 50.seconds
        assertNull(throttle.acquire("a", ReadKind.READ), "a new window")
    }

    @Test
    fun aSubjectHoldingAllItsCallsAtOnceRetriesInASecond() = runTest {
        val throttle = ReadThrottle(clock, ReadLimits(100, 2, 10), maxKeys = 10)
        assertNull(throttle.acquire("a", ReadKind.READ))
        assertNull(throttle.acquire("a", ReadKind.READ))
        assertEquals(1.seconds, throttle.acquire("a", ReadKind.READ))
        throttle.release("a", ReadKind.READ)
        assertNull(throttle.acquire("a", ReadKind.READ), "a slot given back is taken again")
    }

    @Test
    fun aSubjectsWaitsAreCountedAtOnceApartFromItsReads() = runTest {
        val throttle = ReadThrottle(clock, ReadLimits(100, 1, 2), maxKeys = 10)
        assertNull(throttle.acquire("a", ReadKind.READ))
        assertEquals(1.seconds, throttle.acquire("a", ReadKind.READ))
        // The read held does not take a wait's place, nor the waits a read's.
        assertNull(throttle.acquire("a", ReadKind.WAIT))
        assertNull(throttle.acquire("a", ReadKind.WAIT))
        assertEquals(1.seconds, throttle.acquire("a", ReadKind.WAIT))
        throttle.release("a", ReadKind.WAIT)
        assertNull(throttle.acquire("a", ReadKind.WAIT))
        throttle.release("a", ReadKind.READ)
        assertNull(throttle.acquire("a", ReadKind.READ))
    }

    @Test
    fun pastItsSubjectsTheThrottleDropsTheIdleOnesThenTheOldest() = runTest {
        val throttle = ReadThrottle(clock, ReadLimits(100, 1, 1), maxKeys = 2)
        assertNull(throttle.acquire("idle", ReadKind.READ))
        throttle.release("idle", ReadKind.READ)
        assertNull(throttle.acquire("busy", ReadKind.READ))
        now += 61.seconds
        // Full: the idle subject, its window over, is dropped; the busy one stays.
        assertNull(throttle.acquire("c", ReadKind.READ))
        assertEquals(1.seconds, throttle.acquire("busy", ReadKind.READ), "still counted")
        // Full again, nobody idle: the oldest goes.
        assertNull(throttle.acquire("d", ReadKind.READ))
        throttle.release("busy", ReadKind.READ)
        assertNull(throttle.acquire("busy", ReadKind.READ), "counted afresh")
    }

    private object Probe : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            val throttle = koin.get<ReadThrottle>()
            route.throttledReads(throttle) {
                get("/probe/anonymous") { call.respondText("open") }
            }
            route.authenticated {
                throttledReads(throttle) {
                    get("/probe/read") { call.respondText(call.requirePrincipal().subjectId) }
                    get("/probe/fail") { throw OstromException(ErrorKind.NOT_FOUND, "Not found.") }
                }
                throttledWaits(throttle) {
                    get("/probe/wait") { call.respondText("answered") }
                }
            }
        }
    }

    private fun throttleTest(limits: ReadLimits, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
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
                    single { ReadThrottle(clock, limits, maxKeys = 10) }
                },
            )
        }.koin
        application { configureHttp(koin, listOf(Probe)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.read(token: String, path: String = "/probe/read"): HttpResponse =
        client.get(path) { bearerAuth(token) }

    @Test
    fun overItsLimitASubjectIsTold429WithRetryAfterAndOthersAreNot() = throttleTest(ReadLimits(3, 10, 10)) {
        assertEquals(HttpStatusCode.OK, read("alice").status)
        // A wait counts in the calls of the minute with the reads.
        assertEquals("answered", read("alice", "/probe/wait").bodyAsText())
        assertEquals(HttpStatusCode.OK, read("alice").status)
        val refused = read("alice", "/probe/wait")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("61", refused.headers[HttpHeaders.RetryAfter])
        assertTrue("rate_limited" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals("bob", read("bob").bodyAsText())
    }

    @Test
    fun aFailedReadGivesItsSlotBack() = throttleTest(ReadLimits(10, 1, 1)) {
        // One call at once: had the failure kept its slot, the next read would be refused. The slot of a failure is
        // given back before its error is answered.
        assertEquals(HttpStatusCode.NotFound, read("carol", "/probe/fail").status)
        assertEquals(HttpStatusCode.OK, read("carol").status)
    }

    @Test
    fun aCallWithoutAPrincipalIsNotCounted() = throttleTest(ReadLimits(1, 1, 1)) {
        repeat(3) { assertEquals("open", client.get("/probe/anonymous").bodyAsText()) }
    }
}
