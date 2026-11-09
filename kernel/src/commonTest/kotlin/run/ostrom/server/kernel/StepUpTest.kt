// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.BOUND_UNLOCK_WITHIN
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.RejectingPhoneUnlockProofs
import run.ostrom.server.kernel.auth.STEP_UP_REQUIRED
import run.ostrom.server.kernel.auth.STEP_UP_WITHIN
import run.ostrom.server.kernel.auth.StepUpRequiredException
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.kernel.auth.token
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.serverKoin
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A sensitive write from the console or the app needs a sign-in of less than five minutes (web audit n°2 M3). */
class StepUpTest {
    private val now = Instant.parse("2026-11-02T20:00:00Z")

    private fun person(channel: PrincipalChannel, signedIn: Instant?) = AuthenticatedPrincipal(
        "ada", PrincipalKind.USER, setOf(ALL_USER_SCOPES), channel = channel, authenticatedAt = signedIn,
    )

    @Test
    fun aSessionOrTheAppSignedInWithinFiveMinutesWritesAnOlderOrUnknownOneIsAskedToSignInAgain() {
        for (channel in listOf(PrincipalChannel.BROWSER, PrincipalChannel.MOBILE_APP)) {
            person(channel, now - 5.minutes).requireRecentSignIn("create a token", now)
            for (signedIn in listOf(now - 5.minutes - 1.seconds, null)) {
                val refused = assertFailsWith<StepUpRequiredException> {
                    person(channel, signedIn).requireRecentSignIn("create a token", now)
                }
                assertEquals(STEP_UP_REQUIRED, refused.type)
                val minutes = STEP_UP_WITHIN.inWholeMinutes
                val message = "Sign in again to create a token: it needs a sign-in of less than $minutes minutes."
                assertEquals(message, refused.message)
            }
        }
    }

    // Without the identity module, which records the phone's unlocks, no unlock proves a yes from the app.
    @Test
    fun noPhoneUnlockProvesAYesWithoutTheModuleThatRecordsThem() = runTest {
        val bound = serverKoin(module { single<Clock> { Clock.System } }, emptyList()).get<PhoneUnlockProofs>()
        assertIs<RejectingPhoneUnlockProofs>(bound)
        assertFalse(bound.consume("token", "request", now))
    }

    @Test
    fun theProofsTheIdentityModuleBindsAreKeptAndAnUnlockServesAYesForAMinute() {
        val proofs = PhoneUnlockProofs { _, _, _ -> true }
        val identity = object : ServerModule {
            override val name: String = "identity"
            override val koinModule: Module = module { single<PhoneUnlockProofs> { proofs } }
        }
        val koin = serverKoin(module { single<Clock> { Clock.System } }, listOf(identity))
        assertSame(proofs, koin.get<PhoneUnlockProofs>())
        assertEquals(60.seconds, BOUND_UNLOCK_WITHIN)
    }

    @Test
    fun anApiTokenHasNoSignInToRenew() {
        person(PrincipalChannel.API_TOKEN, null).requireRecentSignIn("change a secret", now)
    }

    private object WritesModule : ServerModule {
        override val name: String = "writes"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                post("/sensitive") {
                    call.requireRecentSignIn("change a secret")
                    call.respondText("done")
                }
            }
        }
    }

    // The server's clock (the one a sign-in is stamped with) decides, not the machine's.
    @Test
    fun aRouteChecksTheSignInAgainstTheServersClock() = testApplication {
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<Clock> { object : Clock { override fun now() = this@StepUpTest.now } }
                    single<TokenAuthenticator> {
                        object : TokenAuthenticator {
                            override suspend fun authenticate(token: String) = when (token) {
                                "fresh" -> person(PrincipalChannel.BROWSER, now - 1.minutes)
                                else -> person(PrincipalChannel.BROWSER, now - 6.minutes)
                            }
                        }
                    }
                },
            )
        }.koin
        application { configureHttp(koin, listOf(WritesModule)) }

        assertEquals("done", client.post("/sensitive") { bearerAuth("fresh") }.bodyAsText())
        val stale = client.post("/sensitive") { bearerAuth("stale") }
        assertEquals(HttpStatusCode.Forbidden, stale.status)
        assertTrue(STEP_UP_REQUIRED in stale.bodyAsText(), stale.bodyAsText())
    }

    // An application mounted without the kernel's HTTP setup has no server clock: the machine's one decides.
    @Test
    fun withoutTheKernelsHttpSetupTheMachinesClockDecides() = testApplication {
        val authenticator = object : TokenAuthenticator {
            override suspend fun authenticate(token: String) =
                person(PrincipalChannel.BROWSER, Clock.System.now() - 1.minutes)
        }
        application {
            install(Authentication) { token(authenticator) }
            routing { WritesModule.routes(this, koinApplication { }.koin) }
        }
        assertEquals("done", client.post("/sensitive") { bearerAuth("fresh") }.bodyAsText())
    }
}
