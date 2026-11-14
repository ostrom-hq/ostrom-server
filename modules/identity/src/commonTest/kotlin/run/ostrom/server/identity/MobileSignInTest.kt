// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.fakes.offered
import run.ostrom.server.identity.domain.ANSWER_SCOPE
import run.ostrom.server.identity.domain.APP_REDIRECT_URI
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.CompleteMobileSignInUseCaseImpl
import run.ostrom.server.identity.domain.CompleteOAuthSignInUseCaseImpl
import run.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import run.ostrom.server.identity.domain.ExchangeMobileCodeUseCaseImpl
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.InvalidMobileCodeException
import run.ostrom.server.identity.domain.InvalidMobileSignInException
import run.ostrom.server.identity.domain.InvalidSignInStateException
import run.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import run.ostrom.server.identity.domain.MOBILE_ACCESS_DENIED
import run.ostrom.server.identity.domain.MOBILE_LINK_REQUIRED
import run.ostrom.server.identity.domain.MOBILE_SIGNUP_CLOSED
import run.ostrom.server.identity.domain.MOBILE_REFUSED
import run.ostrom.server.identity.domain.ListTokensUseCaseImpl
import run.ostrom.server.identity.domain.MAX_PHONES_PER_USER
import run.ostrom.server.identity.domain.MOBILE_SCOPES
import run.ostrom.server.identity.domain.MOBILE_UNLOCKED_SCOPES
import run.ostrom.server.identity.domain.MobileCallback
import run.ostrom.server.identity.domain.MobileClient
import run.ostrom.server.identity.domain.MobileUnlockRefusedException
import run.ostrom.server.identity.domain.RateLimitedException
import run.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.SignUpPolicyImpl
import run.ostrom.server.identity.domain.RevokeTokenUseCaseImpl
import run.ostrom.server.identity.domain.RotateTokenUseCaseImpl
import run.ostrom.server.identity.domain.SignInMethods
import run.ostrom.server.identity.domain.SignInServices
import run.ostrom.server.identity.domain.StartOAuthSignInUseCaseImpl
import run.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.TokenNotFoundException
import run.ostrom.server.identity.domain.UnlockMobileAppUseCaseImpl
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.domain.grantedScopes
import run.ostrom.server.identity.domain.isPkceVerifier
import run.ostrom.server.identity.domain.mobileClientOf
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.FakeOAuthProvider
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryMobileCodesRepository
import run.ostrom.server.identity.fakes.InMemoryMobileTokensRepository
import run.ostrom.server.identity.fakes.InMemoryOAuthAttemptsRepository
import run.ostrom.server.identity.fakes.InMemoryUsersRepository
import run.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.fakes.InMemorySetupLinks
import run.ostrom.server.identity.fakes.openSignUp
import run.ostrom.server.identity.presentation.confirmPage
import run.ostrom.server.identity.presentation.toUrl
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
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
            .offered()
    private val callback = { provider: IdentityProvider -> "https://api.test/v1/auth/${provider.key}/callback" }
    private val services = SignInServices(generator, hasher, limiter, clock, callback)
    private val resolve = ResolveUserUseCaseImpl(users, openSignUp(clock), RecordingAuditLog(), clock)
    private val start = StartOAuthSignInUseCaseImpl(methods, attempts, services)
    private val completeMobile = CompleteMobileSignInUseCaseImpl(methods, attempts, services, resolve, codes)
    private val completeBrowser = CompleteOAuthSignInUseCaseImpl(
        methods, attempts, services, resolve, LinkIdentityUseCaseImpl(users, RecordingAuditLog(), clock),
        StartWebSessionUseCaseImpl(InMemoryWebSessionsRepository(), generator, hasher, clock),
    )
    private val exchange = ExchangeMobileCodeUseCaseImpl(codes, mobileTokens, apiTokens, users, services, audit)
    private val unlock = UnlockMobileAppUseCaseImpl(mobileTokens, hasher, limiter, audit, clock)
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
        refused.forEach { query ->
            assertFailsWith<InvalidMobileSignInException>(query.toString()) {
                mobileClientOf(query[0], query[1], query[2], query[3])
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
        // The page that asks before going back to the app says who signed in (AUDIT-SECU-APP-2 N1).
        assertEquals(issued.user.login, back.login)
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
    fun aStrangerOnAnInviteOnlyInstanceIsSentBackToTheAppWithoutAccount() = runTest {
        val closed = SignUpPolicyImpl({ SignUpMode.INVITE }, InMemorySetupLinks(), null, hasher, clock)
        val closedResolve = ResolveUserUseCaseImpl(users, closed, RecordingAuditLog(), clock)
        val closedMobile = CompleteMobileSignInUseCaseImpl(methods, attempts, services, closedResolve, codes)
        val state = startMobile()

        val outcome = closedMobile("github", state, state, "good", null)

        assertEquals(MobileCallback(APP_REDIRECT_URI, "app-state", error = MOBILE_SIGNUP_CLOSED), outcome)
        assertTrue(users.users.isEmpty() && codes.codes.isEmpty())
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

    // Q-APP-STEPUP-RISK: the unlock names the request a yes is for; each unlock replaces the last one's request.
    @Test
    fun anUnlockRecordsTheRequestItIsForAndAnUnlockWithoutOneUnbinds() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        val issued = exchange(code, verifier, APP_REDIRECT_URI, "ip")
        val token = issued.token.token

        unlock(token.userId, token.id, issued.unlockKey, "ip", "request-1")
        assertEquals("request-1", mobileTokens.unlockedFor[token.id])
        unlock(token.userId, token.id, issued.unlockKey, "ip", "r".repeat(500))
        assertEquals("r".repeat(64), mobileTokens.unlockedFor[token.id], "an id no request has is cut, never stored")
        unlock(token.userId, token.id, issued.unlockKey, "ip")
        assertNull(mobileTokens.unlockedFor.getValue(token.id))
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
        assertEquals(MOBILE_SCOPES - MOBILE_UNLOCKED_SCOPES, before.scopes, "No yes nor answer before the first unlock")
        assertNull(before.orgId)
        assertNull(before.authenticatedAt)

        clock.now += 1.days
        assertEquals(clock.now, unlock(token.userId, token.id, issued.unlockKey, "ip"))
        val unlocked = assertNotNull(authenticator.authenticate(issued.token.secret))
        assertEquals(clock.now, unlocked.authenticatedAt)
        assertEquals(MOBILE_SCOPES, unlocked.scopes)
        assertEquals("token.unlocked", audit.entries.last().action)
        assertEquals(listOf("mobile-unlock:${token.id}:ip"), limiter.keys.filter { it.startsWith("mobile-unlock") })

        clock.now += 5.minutes
        assertTrue(ANSWER_SCOPE in assertNotNull(authenticator.authenticate(issued.token.secret)).scopes)
        clock.now += 1.seconds
        val stale = assertNotNull(authenticator.authenticate(issued.token.secret))
        assertEquals(MOBILE_SCOPES - MOBILE_UNLOCKED_SCOPES, stale.scopes, "Five minutes after the unlock, no yes")
        assertTrue("waits:read" in stale.scopes, "The waits asked of it still read")
    }

    @Test
    fun unlocksAreRateLimitedPerToken() = runTest {
        limiter.allow = false

        assertFailsWith<RateLimitedException> { unlock(Uuid.random(), Uuid.random(), "key", "ip") }
    }

    @Test
    fun theAppTokenIsListedAndRevokedInTheConsoleButNeverRotated() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        val issued = exchange(code, verifier, APP_REDIRECT_URI, "ip").token
        val token = issued.token

        assertEquals(listOf(token.id), ListTokensUseCaseImpl(apiTokens, clock)(token.userId).map { it.id })
        assertFailsWith<TokenNotFoundException> {
            RotateTokenUseCaseImpl(apiTokens, generator, hasher, audit, clock)(token.userId, token.id)
        }
        RevokeTokenUseCaseImpl(apiTokens, audit, clock)(token.userId, token.id)
        assertNull(authenticator.authenticate(issued.secret))
    }

    @Test
    fun aSixthPhoneSignsInAndTheOldestIsSignedOut() = runTest {
        val phones = (1..MAX_PHONES_PER_USER + 1).map {
            clock.now += 1.seconds
            val state = startMobile()
            val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
            exchange(code, verifier, APP_REDIRECT_URI, "ip").token
        }

        assertNull(authenticator.authenticate(phones.first().secret), "The oldest phone is signed out")
        phones.drop(1).forEach { assertNotNull(authenticator.authenticate(it.secret)) }
        val revoked = audit.entries.filter { it.action == "token.revoked" }
        assertEquals(listOf(phones.first().token.id.toString()), revoked.map { it.targetId })
    }

    @Test
    fun anUnlockNeedsTheKeyOfThatLiveAppToken() = runTest {
        val state = startMobile()
        val code = assertNotNull(completeMobile("github", state, state, "good", null).code)
        val issued = exchange(code, verifier, APP_REDIRECT_URI, "ip")
        val token = issued.token.token

        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "wrong", "ip") }
        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "", "ip") }
        assertFailsWith<MobileUnlockRefusedException> { unlock(token.userId, token.id, "k".repeat(129), "ip") }
        assertFailsWith<MobileUnlockRefusedException> { unlock(Uuid.random(), token.id, issued.unlockKey, "ip") }
        clock.now += 31.days
        assertFailsWith<MobileUnlockRefusedException>("Expired") {
            unlock(token.userId, token.id, issued.unlockKey, "ip")
        }
        assertNull(apiTokens.tokens.values.single().authenticatedAt)
    }

    @Test
    fun anAppTokenNeverGrantsMoreThanTheAppScopes() = runTest {
        val stored = setOf("orgs:write", ANSWER_SCOPE, "profile:read")
        assertEquals(setOf(ANSWER_SCOPE, "profile:read"), grantedScopes(TokenKind.MOBILE, stored))

        val userId = Uuid.random()
        apiTokens.tokens["h(osm_x)"] = ApiToken(
            Uuid.random(), userId, null, "Ostrom app", TokenKind.MOBILE, stored, "osm_x", null, clock.now,
            clock.now + 1.days, null, null, authenticatedAt = clock.now,
        )
        assertEquals(setOf(ANSWER_SCOPE, "profile:read"), authenticator.authenticate("osm_x")?.scopes)
    }

    @Test
    fun theConfirmationPageSaysWhoSignedInAndLinksOnlyToTheApp() {
        val page = MobileCallback(APP_REDIRECT_URI, "s/1", code = "c 1", login = "ada<b>").confirmPage()
        assertTrue("href=\"ostrom://auth/callback?code=c+1&amp;state=s%2F1\"" in page, page)
        assertTrue("@ada&lt;b&gt;" in page, "The login is escaped: $page")
        assertTrue("<script" !in page, "The person taps: nothing goes to the app by itself")
        assertTrue("<form" !in page && "<img" !in page, "Nothing else leaves the page")
        val unnamed = MobileCallback(APP_REDIRECT_URI, "s", code = "c").confirmPage()
        assertTrue("Sign in to the Ostrom app?" in unnamed, "Without a login, the page names nobody: $unnamed")
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
