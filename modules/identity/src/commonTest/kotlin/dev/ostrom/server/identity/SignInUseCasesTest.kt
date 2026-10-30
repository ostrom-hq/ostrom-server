// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AccountLinkRequiredException
import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.InvalidEmailException
import dev.ostrom.server.identity.domain.InvalidMagicLinkException
import dev.ostrom.server.identity.domain.InvalidSignInStateException
import dev.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailNotSentException
import dev.ostrom.server.identity.domain.ProviderNotConfiguredException
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.RateLimiter
import dev.ostrom.server.identity.domain.RequestMagicLinkUseCaseImpl
import dev.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.SignInServices
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.VerifyMagicLinkUseCaseImpl
import dev.ostrom.server.identity.domain.safeRedirectPath
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.FakeOAuthProvider
import dev.ostrom.server.identity.fakes.InMemoryMagicLinksRepository
import dev.ostrom.server.identity.fakes.InMemoryOAuthAttemptsRepository
import dev.ostrom.server.identity.fakes.InMemoryUsersRepository
import dev.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.RecordingMailSender
import dev.ostrom.server.identity.fakes.ScriptedRateLimiter
import dev.ostrom.server.identity.fakes.SettableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SignInUseCasesTest {
    private val clock = SettableClock()
    private val generator = CountingSecretGenerator()
    private val hasher = FakeHasher()
    private val limiter = ScriptedRateLimiter()
    private val attempts = InMemoryOAuthAttemptsRepository()
    private val links = InMemoryMagicLinksRepository()
    private val mail = RecordingMailSender()
    private val users = InMemoryUsersRepository()
    private val sessions = InMemoryWebSessionsRepository()
    private val github = FakeOAuthProvider(IdentityProvider.GITHUB)
    private val google = FakeOAuthProvider(IdentityProvider.GOOGLE)
    private val methods =
        SignInMethods(mapOf(IdentityProvider.GITHUB to github, IdentityProvider.GOOGLE to google), email = true)
    private val resolve = ResolveUserUseCaseImpl(users, RecordingAuditLog(), clock)
    private val startSession = StartWebSessionUseCaseImpl(sessions, generator, hasher, clock)
    private val callback = { provider: IdentityProvider -> "https://api.test/v1/auth/${provider.key}/callback" }
    private val services = SignInServices(generator, hasher, limiter, clock, callback)
    private val start = StartOAuthSignInUseCaseImpl(methods, attempts, services)
    private val link = LinkIdentityUseCaseImpl(users, RecordingAuditLog(), clock)
    private val complete = CompleteOAuthSignInUseCaseImpl(methods, attempts, services, resolve, link, startSession)
    private val request = RequestMagicLinkUseCaseImpl(
        methods, links, mail, services,
        { to, link -> Mail(to, "Sign in", "<a href=\"$link\">", link) }, { "https://app.test/auth/email#token=$it" },
    )
    private val verify = VerifyMagicLinkUseCaseImpl(links, hasher, resolve, startSession, clock)

    @Test
    fun aProviderSignInStartsWithPkceAndANonceAndEndsWithASession() = runTest {
        val started = start("github", "/repos?tab=1", "1.2.3.4")

        assertEquals("secret1", started.state)
        assertEquals(
            "https://idp.example/authorize?state=secret1&challenge=s256(secret2)&nonce=secret3" +
                "&redirect=https://api.test/v1/auth/github/callback",
            started.authorizationUrl,
        )
        assertEquals(listOf("oauth-start:1.2.3.4"), limiter.keys)
        val attempt = attempts.attempts.getValue("h(secret1)")
        assertEquals(clock.now + 10.minutes, attempt.expiresAt)

        val signedIn = complete("github", "secret1", "secret1", "good")
        assertEquals(
            listOf("good", "secret2", "secret3", "https://api.test/v1/auth/github/callback"),
            github.exchanges.single(),
        )
        assertEquals("/repos?tab=1", signedIn.redirectPath)
        assertEquals("ada@example.com", signedIn.user.primaryEmail)
        assertEquals(signedIn.user.id, sessions.sessions.values.single().userId)
        assertFailsWith<InvalidSignInStateException>("An attempt completes once") {
            complete("github", "secret1", "secret1", "good")
        }
    }

    @Test
    fun anUnsafeRedirectBecomesTheConsoleHome() = runTest {
        start("google", "https://evil.example", "ip")

        assertEquals("/", attempts.attempts.values.single().redirectPath)
    }

    @Test
    fun anUnknownOrUnconfiguredProviderIsRefused() = runTest {
        val onlyGithub = StartOAuthSignInUseCaseImpl(
            SignInMethods(mapOf(IdentityProvider.GITHUB to github), email = false),
            attempts, services,
        )

        assertFailsWith<ProviderNotConfiguredException> { onlyGithub("google", null, "ip") }
        assertFailsWith<ProviderNotConfiguredException> { onlyGithub("gitlab", null, "ip") }
        assertFailsWith<ProviderNotConfiguredException> { complete("email", "s", "s", "good") }
    }

    @Test
    fun startingIsRateLimitedPerAddress() = runTest {
        limiter.allow = false

        assertFailsWith<RateLimitedException> { start("github", null, "ip") }
        assertTrue(attempts.attempts.isEmpty())
    }

    @Test
    fun aCallbackMustCarryTheStateOfTheBrowserThatStarted() = runTest {
        start("github", null, "ip")

        assertFailsWith<InvalidSignInStateException> { complete("github", null, "secret1", "good") }
        assertFailsWith<InvalidSignInStateException> { complete("github", "", "secret1", "good") }
        assertFailsWith<InvalidSignInStateException> { complete("github", "secret1", null, "good") }
        assertFailsWith<InvalidSignInStateException> { complete("github", "secret1", "other", "good") }
        assertFailsWith<InvalidSignInStateException> { complete("github", "unknown", "unknown", "good") }
        // The attempt is still there: none of the refusals above consumed it.
        assertEquals("/", complete("github", "secret1", "secret1", "good").redirectPath)
    }

    @Test
    fun aStateOfAnotherProviderOrExpiredIsRefused() = runTest {
        start("github", null, "ip")
        assertFailsWith<InvalidSignInStateException> { complete("google", "secret1", "secret1", "good") }

        start("github", null, "ip")
        clock.now += 11.minutes
        assertFailsWith<InvalidSignInStateException> { complete("github", "secret4", "secret4", "good") }
    }

    @Test
    fun aMissingOrRefusedCodeFails() = runTest {
        start("github", null, "ip")
        assertFailsWith<ProviderRejectedException> { complete("github", "secret1", "secret1", null) }
        start("github", null, "ip")
        assertFailsWith<ProviderRejectedException> { complete("github", "secret4", "secret4", "") }
        start("github", null, "ip")
        assertFailsWith<ProviderRejectedException> { complete("github", "secret7", "secret7", "bad") }
        assertTrue(users.users.isEmpty())
    }

    @Test
    fun anIdentityThatCannotMergeIsLinkedOnlyFromTheSignedInAccount() = runTest {
        start("github", null, "ip")
        val ada = complete("github", "secret1", "secret1", "good").user
        // Google for an address outside Gmail and outside a Workspace of its domain: no sign-in into Ada's account.
        start("google", null, "ip")
        assertFailsWith<AccountLinkRequiredException> { complete("google", "secret5", "secret5", "good") }
        assertEquals(listOf("github"), users.listIssuers(ada.id))

        // Ada, signed in, starts the link herself: the callback links Google to her.
        start("google", "/settings", "ip", linkTo = ada.id)
        assertEquals(ada.id, attempts.attempts.getValue("h(secret8)").linkUserId)
        val linked = complete("google", "secret8", "secret8", "good")
        assertEquals(ada.id, linked.user.id)
        assertEquals("/settings", linked.redirectPath)
        assertEquals(listOf("github", "google"), users.listIssuers(ada.id))
    }

    @Test
    fun aMagicLinkIsSentThenSignsInOnce() = runTest {
        request(" Ada@Example.com ", "/settings", "1.2.3.4")

        val sent = mail.sent.single()
        assertEquals("ada@example.com", sent.to)
        assertEquals("https://app.test/auth/email#token=secret1", sent.text)
        assertEquals(listOf("magic-link:1.2.3.4", "magic-link-to:ada@example.com"), limiter.keys)
        assertEquals(clock.now + 15.minutes, links.links.getValue("h(secret1)").expiresAt)

        val signedIn = verify("secret1")
        assertEquals("/settings", signedIn.redirectPath)
        assertEquals("ada@example.com", signedIn.user.primaryEmail)
        assertEquals(listOf("email"), users.listIssuers(signedIn.user.id))
        assertFailsWith<InvalidMagicLinkException> { verify("secret1") }
    }

    @Test
    fun aMagicLinkSignsInTheAccountThatVerifiedTheSameAddress() = runTest {
        val existing = resolve(VerifiedIdentity(IdentityProvider.GITHUB, "7", "ada@example.com", true, "Ada"))
        request("ada@example.com", null, "ip")

        assertEquals(existing.id, verify("secret1").user.id)
    }

    @Test
    fun anExpiredUnknownOrOversizedLinkIsRefused() = runTest {
        request("ada@example.com", null, "ip")
        clock.now += 16.minutes

        assertFailsWith<InvalidMagicLinkException> { verify("secret1") }
        assertFailsWith<InvalidMagicLinkException> { verify("nope") }
        assertFailsWith<InvalidMagicLinkException> { verify("") }
        assertFailsWith<InvalidMagicLinkException> { verify("x".repeat(129)) }
    }

    @Test
    fun linkRequestsAreLimitedPerClientLoudlyAndPerRecipientSilently() = runTest {
        limiter.allow = false
        assertFailsWith<RateLimitedException> { request("ada@example.com", null, "ip") }

        val perRecipient = RequestMagicLinkUseCaseImpl(
            methods, links, mail,
            SignInServices(
                generator, hasher,
                object : RateLimiter {
                    override suspend fun tryAcquire(key: String, limit: Int, window: Duration) =
                        !key.startsWith("magic-link-to:")
                },
                clock, callback,
            ),
            { to, link -> Mail(to, "s", link, link) }, { it },
        )
        perRecipient("ada@example.com", null, "ip")
        assertTrue(mail.sent.isEmpty())
        assertTrue(links.links.isEmpty())
    }

    @Test
    fun linkRequestsCheckTheAddressAndTheConfiguration() = runTest {
        assertFailsWith<InvalidEmailException> { request("not-an-email", null, "ip") }
        val unconfigured = RequestMagicLinkUseCaseImpl(
            SignInMethods(emptyMap(), email = false), links, mail, services,
            { to, link -> Mail(to, "s", link, link) }, { it },
        )
        assertFailsWith<ProviderNotConfiguredException> { unconfigured("ada@example.com", null, "ip") }
        mail.fail = true
        assertFailsWith<MailNotSentException> { request("ada@example.com", null, "ip") }
    }

    @Test
    fun theOfferedMethodsAreListedInOrder() {
        assertEquals(listOf("github", "google", "email"), methods.keys)
        assertEquals(emptyList(), SignInMethods(emptyMap(), email = false).keys)
    }

    @Test
    fun redirectsStayOnTheConsole() {
        listOf("/", "/repos", "/a/b?c=d&e=f#g", "/device?code=BCDF-GHJK", "/x?u=https://y").forEach {
            assertEquals(it, safeRedirectPath(it), it)
        }
        listOf(
            null, "", "repos", "//evil.example", "/\\evil.example", "https://evil.example", "/javascript:alert(1)",
            "/a b", "/a\tb", "/a\u0000", "/" + "a".repeat(512),
        ).forEach { assertEquals("/", safeRedirectPath(it), it.toString()) }
        assertEquals("/trimmed", safeRedirectPath("  /trimmed "))
    }
}
