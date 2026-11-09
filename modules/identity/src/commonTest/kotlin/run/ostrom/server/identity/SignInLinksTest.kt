// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.CreateSignInLinkUseCaseImpl
import run.ostrom.server.identity.domain.ExchangeSignInLinkUseCaseImpl
import run.ostrom.server.identity.domain.InvalidSignInLinkException
import run.ostrom.server.identity.domain.RateLimitedException
import run.ostrom.server.identity.domain.SIGN_IN_LINK_LIFETIME
import run.ostrom.server.identity.domain.SignInLink
import run.ostrom.server.identity.domain.SignInLinksRepository
import run.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.kernel.errors.ErrorKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Sign-in links (`opk_`): made by a signed-in person, used once within 15 minutes for a browser session (S2-08). */
class SignInLinksTest {
    /** Links by token hash, consumed like the PostgreSQL statement: once, before expiry. */
    private class InMemorySignInLinksRepository : SignInLinksRepository {
        val links = mutableMapOf<String, Pair<SignInLink, Boolean>>()

        override suspend fun create(link: SignInLink, tokenHash: String) {
            links[tokenHash] = link to false
        }

        override suspend fun consume(tokenHash: String, now: Instant): SignInLink? {
            val found = links[tokenHash]?.takeIf { (link, used) -> !used && link.expiresAt > now }?.first
            if (found != null) links[tokenHash] = found to true
            return found
        }
    }

    private val links = InMemorySignInLinksRepository()
    private val hasher = FakeHasher()
    private val limiter = ScriptedRateLimiter()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val sessions = InMemoryWebSessionsRepository()
    private val startSession = StartWebSessionUseCaseImpl(sessions, CountingSecretGenerator(), hasher, clock)
    private val create = CreateSignInLinkUseCaseImpl(links, CountingSecretGenerator(), hasher, limiter, audit, clock)
    private val exchange = ExchangeSignInLinkUseCaseImpl(links, hasher, limiter, startSession, audit, clock)
    private val userId = Uuid.parse("00000000-0000-4000-8000-0000000000aa")

    @Test
    fun aLinkOpensOneSessionOfItsPersonWithinFifteenMinutes() = runTest {
        val created = create(userId)
        assertEquals("opk_secret1", created.token)
        assertEquals(clock.now + SIGN_IN_LINK_LIFETIME, created.link.expiresAt)
        assertEquals(created.link to false, links.links["h(opk_secret1)"], "Only the hash is stored")

        clock.now += SIGN_IN_LINK_LIFETIME - 1.seconds
        val started = exchange(created.token, "10.0.0.1")
        assertEquals(userId, started.session.userId)
        assertEquals(listOf("sign-in-link:$userId", "sign-in-link-use:10.0.0.1"), limiter.keys)
        assertEquals(listOf("sign_in_link.created", "sign_in_link.used"), audit.entries.map { it.action })
        assertTrue(audit.entries.all { it.actorId == userId && it.targetId == created.link.id.toString() })

        val reused = assertFailsWith<InvalidSignInLinkException> { exchange(created.token, "10.0.0.1") }
        assertEquals(ErrorKind.UNAUTHORIZED, reused.kind)
    }

    @Test
    fun anExpiredUnknownOrMalformedLinkOpensNothing() = runTest {
        val created = create(userId)
        clock.now += SIGN_IN_LINK_LIFETIME
        assertFailsWith<InvalidSignInLinkException> { exchange(created.token, "a") }
        assertFailsWith<InvalidSignInLinkException> { exchange("opk_unknown", "a") }
        // Never hashed nor counted: another kind of secret, or a value longer than any we issue.
        listOf("oss_secret1", "osp_x", "", "opk_" + "x".repeat(200)).forEach { token ->
            assertFailsWith<InvalidSignInLinkException>(token) { exchange(token, "a") }
        }
        assertEquals(2, limiter.keys.count { it.startsWith("sign-in-link-use:") })
    }

    @Test
    fun makingAndUsingLinksIsRateLimited() = runTest {
        val created = create(userId)
        limiter.allow = false

        assertFailsWith<RateLimitedException> { create(userId) }
        assertFailsWith<RateLimitedException> { exchange(created.token, "a") }
        assertEquals(created.link to false, links.links["h(opk_secret1)"], "A refused attempt does not use the link")
    }
}
