// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ANSWER_SCOPE
import dev.ostrom.server.identity.domain.APP_REDIRECT_URI
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.CompleteMobileSignInUseCaseImpl
import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import dev.ostrom.server.identity.domain.ExchangeMobileCodeUseCaseImpl
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.InvalidMobileCodeException
import dev.ostrom.server.identity.domain.InvalidMobileSignInException
import dev.ostrom.server.identity.domain.InvalidSignInStateException
import dev.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import dev.ostrom.server.identity.domain.MOBILE_ACCESS_DENIED
import dev.ostrom.server.identity.domain.MOBILE_LINK_REQUIRED
import dev.ostrom.server.identity.domain.MOBILE_REFUSED
import dev.ostrom.server.identity.domain.MOBILE_SCOPES
import dev.ostrom.server.identity.domain.MobileCallback
import dev.ostrom.server.identity.domain.MobileClient
import dev.ostrom.server.identity.domain.MobileUnlockRefusedException
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.SignInServices
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.UnlockMobileAppUseCaseImpl
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.grantedScopes
import dev.ostrom.server.identity.domain.isPkceVerifier
import dev.ostrom.server.identity.domain.mobileClientOf
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.FakeOAuthProvider
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryMobileCodesRepository
import dev.ostrom.server.identity.fakes.InMemoryMobileTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryOAuthAttemptsRepository
import dev.ostrom.server.identity.fakes.InMemoryUsersRepository
import dev.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.ScriptedRateLimiter
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.presentation.toUrl
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The phone app's sign-in (S2-24): PKCE start, one-time code, exchange, and the biometric unlock (S2-54). */
class MobileSignInTest {
    private val clock = SettableClock()
    private val generator = CountingSecretGenerator()
    private val hasher = FakeHasher()
    private val limiter = ScriptedRateLimiter()
    private val attempts = InMemoryOAuthAttemptsRepository()
    private val users = InMemoryUsersRepository()
    private val audit = RecordingAuditLog()
    private val apiTokens = InMemoryApiTokensRepository()
    private val mobileTokens = InMemoryMobileTokensRepository(apiTokens)
    private val codes = InMemoryMobileCodesRepository()
    private val github = FakeOAuthProvider(IdentityProvider.GITHUB)
    private val google = FakeOAuthProvider(IdentityProvider.GOOGLE)
    private val methods =
        SignInMethods(mapOf(IdentityProvider.GITHUB to github, IdentityProvider.GOOGLE to google), email = false)
    private val callback = { provider: IdentityProvider -> "https://api.test/v1/auth/${provider.key}/callback" }
    private val services = SignInServices(generator, hasher, limiter, clock, callback)
    private val resolve = ResolveUserUseCaseImpl(users, RecordingAuditLog(), clock)
    private val start = StartOAuthSignInUseCaseImpl(methods, attempts, services)
    private val completeMobile = CompleteMobileSignInUseCaseImpl(methods, attempts, services, resolve, codes)
    private val completeBrowser = CompleteOAuthSignInUseCaseImpl(
        methods, attempts, services, resolve, LinkIdentityUseCaseImpl(users, RecordingAuditLog(), clock),
        StartWebSessionUseCaseImpl(InMemoryWebSessionsRepository(), generator, hasher, clock),
    )
    private val exchange = ExchangeMobileCodeUseCaseImpl(codes, mobileTokens, users, services, audit)
    private val unlock = UnlockMobileAppUseCaseImpl(mobileTokens, hasher, clock)
    private val authenticator =
        CredentialAuthenticatorImpl(apiTokens, InMemoryWebSessionsRepository(), hasher, clock, emptySet(), "")

    private val verifier = "v".repeat(43)
    private val app = MobileClient(APP_REDIRECT_URI, "s256($verifier)", "app-state")

    /** Starts a mobile sign-in and returns the server's state (`secret1`, `secret4`… in order). */
    private suspend fun startMobile(provider: String = "github"): String =
        start.mobile(provider, "ip", app).state

    @Test
    fun theAppParametersAreAllOrNothingAndStrict() {
        val challenge = "A".repeat(43)
        assertNull(mobileClientOf(null, null, null, null), "A browser sign-in")
        assertNull(mobileClientOf(null, "state", null, null), "A state alone is not the app")
        assertEquals(
            MobileClient(APP_REDIRECT_URI, challenge, "s-1"),
            mobileClientOf(APP_REDIRECT_URI, "s-1", challenge, "S256"),
        )
        val refused = listOf(
            listOf("https://evil.example/cb", "s", challenge, "S256"),
            listOf("ostrom://auth/callback/evil", "s", challenge, "S256"),
            listOf(APP_REDIRECT_URI, "s", challenge, "plain"),
            listOf(APP_REDIRECT_URI, "s", challenge, null),
            listOf(APP_REDIRECT_URI, "s", null, "S256"),
            listOf(APP_REDIRECT_URI, "s", "A".repeat(42), "S256"),
            listOf(APP_REDIRECT_URI, "s", "A".repeat(42) + "=", "S256"),
            listOf(APP_REDIRECT_URI, null, challenge, "S256"),
            listOf(APP_REDIRECT_URI, "", challenge, "S256"),
            listOf(APP_REDIRECT_URI, "a b", challenge, "S256"),
            listOf(null, "s", challenge, "S256"),
            listOf(null, null, null, "S256"),
        )
        refused.forEach { (uri, state, code, method) ->
            assertFailsWith<InvalidMobileSignInException>("$uri $state $code $method") {
                mobileClientOf(uri, state, code, method)
            }
        }
    }

    @Test
    fun aVerifierIsFortyThreeToOneHundredTwentyEightUnreservedCharacters() {
        assertTrue(isPkceVerifier("a-._~".repeat(9).take(43)))
        assertTrue(isPkceVerifier("x".repeat(128)))
        assertFalse(isPkceVerifier("x".repeat(42)))
        assertFalse(isPkceVerifier("x".repeat(129)))
        assertFalse(isPkceVerifier("x".repeat(42) + "/"))
    }

    @Test
    fun theAppSignsInWithAOneTimeCodeAndGetsAnAppTokenWithItsUnlockKey() = runTest {
        val state = startMobile()
        val attempt = attempts.attempts.getValue("h($state)")
        assertEquals(app, attempt.mobile)
        // A new phone is never signed in silently: the provider asks again.
        assertTrue(github.exchanges.isEmpty())

        val back = completeMobile("github", state, state, "good", null)
        assertEquals(APP_REDIRECT_URI, back.redirectUri)
        assertEquals("app-state", back.state)
        assertNull(back.error)
        val code = assertNotNull(back.code)
        val stored = codes.codes.getValue("h($code)")
        assertEquals(clock.now + 60.seconds, stored.expiresAt)
        assertEquals("s256($verifier)", stored.codeChallenge)

        val issued = exchange(code, verifier, APP_REDIRECT_URI, "1.2.3.4")
        assertTrue(issued.token.secret.startsWith("osm_"))
        assertEquals(TokenKind.MOBILE, issued.token.token.kind)
        assertEquals(MOBILE_SCOPES, issued.token.token.scopes)
        assertEquals(clock.now + 30.days, issued.token.token.expiresAt)
        assertEquals(clock.now + 30.days, issued.expiresAt)
        assertNull(issued.token.token.authenticatedAt, "No yes before the first biometric unlock")
        assertEquals("ada@example.com", issued.user.primaryEmail)
        assertEquals("h(${issued.unlockKey})", mobileTokens.unlockKeys.getValue(issued.token.token.id))
        assertEquals(listOf("mobile-token:1.2.3.4"), limiter.keys.filter { it.startsWith("mobile-token") })
        assertEquals("token.created", audit.entries.single().action)
        assertEquals(mapOf("kind" to "mobile"), audit.entries.single().details)
    }

    @Test
    fun theConsentPageOfAMobileSignInAlwaysAsksAgain() = runTest {
        assertTrue(start.mobile("github", "ip", app).authorizationUrl.endsWith("reauth=true"))
        assertTrue(start("github", null, "ip").authorizationUrl.endsWith("reauth=false"))
    }

    @Test
    fun aMobileAttemptNeverOpensABrowserSessionAndABrowserAttemptNeverReachesTheApp() = runTest {
        val mobile = startMobile()
        assertFailsWith<InvalidSignInStateException> { completeBrowser("github", mobile, mobile, "good") }
        val browser = start("github", null, "ip").state
        assertFailsWith<InvalidSignInStateException> { completeMobile("github", browser, browser, "good", null) }
        assertFailsWith<InvalidSignInStateException> { completeMobile("github", "unknown", "unknown", "good", null) }
    }

    @Test
    fun aRefusalReachesTheAppAsAnErrorWithItsState() = runTest {
        suspend fun outcome(code: String?, error: String? = null): MobileCallback {
            val state = startMobile()
            return completeMobile("github", state, state, code, error)
        }

        assertEquals(MobileCallback(APP_REDIRECT_URI, "app-state", error = MOBILE_ACCESS_DENIED), outcome("good", "x"))
        assertEquals(MOBILE_REFUSED, outcome(null).error)
        assertEquals(MOBILE_REFUSED, outcome("").error)
        assertEquals(MOBILE_REFUSED, outcome("bad").error)
        assertTrue(codes.codes.isEmpty())
        assertTrue(users.users.isEmpty())
    }

    @Test
    fun anIdentityThatCannotMergeAsksTheAppToLinkItFromTheConsole() = runTest {
        val first = startMobile()
        completeMobile("github", first, first, "good", null)
        val second = startMobile("google")

        assertEquals(MOBILE_LINK_REQUIRED, completeMobile("google", second, second, "good", null).error)
    }

    @Test
    fun aCodeIsSpentByItsFirstExchangeEvenAWrongOne() = runTest {
        suspend fun freshCode(): String {
            val state = startMobile()
            return assertNotNull(completeMobile("github", state, state, "good", null).code)
        }

        val replayed = freshCode()
        exchange(replayed, verifier, APP_REDIRECT_URI, "ip")
        assertFailsWith<InvalidMobileCodeException>("Replayed") { exchange(replayed, verifier, APP_REDIRECT_URI, "ip") }

        val wrongVerifier = freshCode()
        assertFailsWith<InvalidMobileCodeException>("Wrong verifier") {
            exchange(wrongVerifier, "w".repeat(43), APP_REDIRECT_URI, "ip")
        }
        assertFailsWith<InvalidMobileCodeException>("Spent by the wrong verifier") {
            exchange(wrongVerifier, verifier, APP_REDIRECT_URI, "ip")
        }

        val malformedVerifier = freshCode()
        assertFailsWith<InvalidMobileCodeException> { exchange(malformedVerifier, "short", APP_REDIRECT_URI, "ip") }

        val foreign = freshCode()
        assertFailsWith<InvalidMobileCodeException>("Foreign redirect") {
            exchange(foreign, verifier, "https://evil.example/cb", "ip")
        }
        assertFailsWith<InvalidMobileCodeException> { exchange(foreign, verifier, APP_REDIRECT_URI, "ip") }

        val expired = freshCode()
        clock.now += 61.seconds
        assertFailsWith<InvalidMobileCodeException>("Expired") { exchange(expired, verifier, APP_REDIRECT_URI, "ip") }

        assertFailsWith<InvalidMobileCodeException> { exchange("", verifier, APP_REDIRECT_URI, "ip") }
        assertFailsWith<InvalidMobileCodeException> { exchange("c".repeat(129), verifier, APP_REDIRECT_URI, "ip") }
        assertEquals(1, apiTokens.tokens.size, "Only the first exchange issued a token")
    }

    @Test
    fun aCodeOfADeletedAccountIssuesNothing() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        users.users.clear()

        assertFailsWith<InvalidMobileCodeException> { exchange(code, verifier, APP_REDIRECT_URI, "ip") }
        assertTrue(apiTokens.tokens.isEmpty())
    }

    @Test
    fun exchangesAreRateLimitedPerAddress() = runTest {
        limiter.allow = false

        assertFailsWith<RateLimitedException> { exchange("code", verifier, APP_REDIRECT_URI, "ip") }
    }

    @Test
    fun theAppTokenSaysYesOnlyAfterABiometricUnlock() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        val issued = exchange(code, verifier, APP_REDIRECT_URI, "ip")
        val token = issued.token.token

        val before = assertNotNull(authenticator.authenticate(issued.token.secret))
        assertEquals(PrincipalKind.USER, before.kind)
        assertEquals(PrincipalChannel.MOBILE_APP, before.channel)
        assertEquals(token.userId.toString(), before.subjectId)
        assertEquals(MOBILE_SCOPES, before.scopes)
        assertNull(before.orgId)
        assertNull(before.authenticatedAt)

        clock.now += 1.days
        assertEquals(clock.now, unlock(token.userId, token.id, issued.unlockKey))
        assertEquals(clock.now, authenticator.authenticate(issued.token.secret)?.authenticatedAt)
    }

    @Test
    fun anUnlockNeedsTheKeyOfThatLiveAppToken() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        val issued = exchange(code, verifier, APP_REDIRECT_URI, "ip")
        val token = issued.token.token

        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "wrong") }
        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "") }
        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "k".repeat(129)) }
        assertFailsWith<MobileUnlockRefusedException> { unlock(Uuid.random(), token.id, issued.unlockKey) }
        clock.now += 31.days
        assertFailsWith<MobileUnlockRefusedException>("Expired") { unlock(token.userId, token.id, issued.unlockKey) }
        assertNull(apiTokens.tokens.values.single().authenticatedAt)
    }

    @Test
    fun anAppTokenNeverGrantsMoreThanTheAppScopes() = runTest {
        val stored = setOf("orgs:write", ANSWER_SCOPE, "profile:read")
        assertEquals(setOf(ANSWER_SCOPE, "profile:read"), grantedScopes(TokenKind.MOBILE, stored))

        val userId = Uuid.random()
        apiTokens.tokens["h(osm_x)"] = ApiToken(
            Uuid.random(), userId, null, "Ostrom app", TokenKind.MOBILE, stored, "osm_x", null, clock.now,
            clock.now + 1.days, null, null,
        )
        assertEquals(setOf(ANSWER_SCOPE, "profile:read"), authenticator.authenticate("osm_x")?.scopes)
    }

    @Test
    fun theDeepLinkCarriesTheCodeOrTheErrorAndTheAppState() {
        assertEquals(
            "ostrom://auth/callback?code=c+1&state=s%2F1",
            MobileCallback(APP_REDIRECT_URI, "s/1", code = "c 1").toUrl(),
        )
        assertEquals(
            "ostrom://auth/callback?error=refused&state=s",
            MobileCallback(APP_REDIRECT_URI, "s", error = MOBILE_REFUSED).toUrl(),
        )
        assertEquals("ostrom://auth/callback?error=&state=s", MobileCallback(APP_REDIRECT_URI, "s").toUrl())
    }

    @Test
    fun aMobileIdentityIsTheVerifiedOneOfTheProvider() = runTest {
        github.identity = VerifiedIdentity(IdentityProvider.GITHUB, "7", "grace@example.com", true, "Grace")
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)

        assertEquals("Grace", exchange(code, verifier, APP_REDIRECT_URI, "ip").user.displayName)
        assertEquals(
            listOf("good", "secret2", "secret3", "https://api.test/v1/auth/github/callback"),
            github.exchanges.single(),
        )
    }
}
