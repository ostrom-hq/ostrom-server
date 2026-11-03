// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AGENT_SCOPE
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCaseImpl
import dev.ostrom.server.identity.domain.InvalidScopesException
import dev.ostrom.server.identity.domain.InvalidTokenLifetimeException
import dev.ostrom.server.identity.domain.InvalidTokenNameException
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCaseImpl
import dev.ostrom.server.identity.domain.ListTokensUseCaseImpl
import dev.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import dev.ostrom.server.identity.domain.RevokeAgentTokensUseCaseImpl
import dev.ostrom.server.identity.domain.RevokeTokenUseCaseImpl
import dev.ostrom.server.identity.domain.RotateTokenUseCaseImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.TokenNotFoundException
import dev.ostrom.server.identity.domain.TooManyTokensException
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.SettableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TokenUseCasesTest {
    private val tokens = InMemoryApiTokensRepository()
    private val secrets = CountingSecretGenerator()
    private val hasher = FakeHasher()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val create = CreatePersonalTokenUseCaseImpl(tokens, secrets, hasher, audit, clock)
    private val list = ListTokensUseCaseImpl(tokens, clock)
    private val revoke = RevokeTokenUseCaseImpl(tokens, audit, clock)
    private val rotate = RotateTokenUseCaseImpl(tokens, secrets, hasher, audit, clock)
    private val issueAgent = IssueAgentTokenUseCaseImpl(tokens, secrets, hasher, clock)
    private val userId = Uuid.random()

    @Test
    fun aPersonalTokenIsStoredHashedWithItsScopesAndHint() = runTest {
        val issued = create(userId, "  deploy bot ", listOf("orgs:read", "orgs:read", "workflows:write"), 30.days)

        assertEquals("osp_secret1", issued.secret)
        assertEquals("osp_secret1", issued.token.hint)
        assertEquals("deploy bot", issued.token.name)
        assertEquals(setOf("orgs:read", "workflows:write"), issued.token.scopes)
        assertEquals(clock.now + 30.days, issued.token.expiresAt)
        assertEquals(issued.token, tokens.findBySecretHash("h(osp_secret1)"))
        assertEquals("token.created", audit.entries.single().action)
        assertEquals(mapOf("scopes" to "orgs:read workflows:write"), audit.entries.single().details)
    }

    @Test
    fun aTokenWithoutLifetimeNeverExpires() = runTest {
        assertNull(create(userId, "ci", listOf("orgs:read"), null).token.expiresAt)
    }

    @Test
    fun aNameWithAControlOrInvisibleCharacterIsRefusedAndAnyOtherKeptAsWritten() = runTest {
        // The name is the creator's: a governance confirmation shows it, so it can never break a line or hide text.
        val trapped = listOf("ostrom login\nOwner approval", "tab\there", "bell\u0007", "rtl\u202Eevil", "zero\u200Bw")
        for (name in trapped) {
            assertFailsWith<InvalidTokenNameException>(name) { create(userId, name, listOf("orgs:read"), null) }
        }
        val trusted = create(userId, "ostrom login", listOf("orgs:read"), null).token.name
        assertEquals("ostrom login", trusted, "A name like another credential is kept: the console says its type")
        assertEquals("<b>x</b>", create(userId, "<b>x</b>", listOf("orgs:read"), null).token.name)
    }

    @Test
    fun badNamesScopesAndLifetimesAreRefused() = runTest {
        assertFailsWith<InvalidTokenNameException> { create(userId, "  ", listOf("orgs:read"), null) }
        assertFailsWith<InvalidTokenNameException> { create(userId, "x".repeat(101), listOf("orgs:read"), null) }
        assertFailsWith<InvalidScopesException> { create(userId, "t", emptyList(), null) }
        assertFailsWith<InvalidScopesException> { create(userId, "t", listOf("Orgs:Read"), null) }
        assertFailsWith<InvalidScopesException> { create(userId, "t", listOf(AGENT_SCOPE), null) }
        // S2-04: no token is ever issued with the right to answer a gate, even when asked for.
        assertFailsWith<InvalidScopesException> { create(userId, "t", listOf("orgs:read", "gates:answer"), null) }
        assertFailsWith<InvalidScopesException> { create(userId, "t", (1..33).map { "s$it:read" }, null) }
        assertFailsWith<InvalidTokenLifetimeException> { create(userId, "t", listOf("orgs:read"), 1.hours) }
        assertFailsWith<InvalidTokenLifetimeException> { create(userId, "t", listOf("orgs:read"), 367.days) }
    }

    @Test
    fun aUserHasAtMostAHundredLiveTokens() = runTest {
        repeat(MAX_TOKENS_PER_USER) { create(userId, "t$it", listOf("orgs:read"), null) }

        assertFailsWith<TooManyTokensException> { create(userId, "one more", listOf("orgs:read"), null) }
    }

    @Test
    fun theListShowsOnlyLivePersonalAndCliTokens() = runTest {
        val live = create(userId, "live", listOf("orgs:read"), 2.days)
        val revoked = create(userId, "revoked", listOf("orgs:read"), null)
        revoke(userId, revoked.token.id)
        issueAgent(userId, Uuid.random(), "run-1", 1.hours)
        clock.now += 1.seconds
        val expiring = create(userId, "expiring", listOf("orgs:read"), 1.days)
        clock.now += 1.days

        assertEquals(listOf(live.token.id), list(userId).map { it.id })
        assertNotNull(expiring)
    }

    @Test
    fun revokingIsRecordedAndAnUnknownOrAlreadyRevokedTokenIsNotFound() = runTest {
        val issued = create(userId, "t", listOf("orgs:read"), null)

        revoke(userId, issued.token.id)
        assertEquals("token.revoked", audit.entries.last().action)
        assertFailsWith<TokenNotFoundException> { revoke(userId, issued.token.id) }
        assertFailsWith<TokenNotFoundException> { revoke(Uuid.random(), issued.token.id) }
    }

    @Test
    fun rotationKeepsNameScopesAndLifetimeAndRevokesTheOldSecret() = runTest {
        val old = create(userId, "t", listOf("orgs:read"), 10.days)
        clock.now += 1.days

        val new = rotate(userId, old.token.id)
        assertEquals("osp_secret2", new.secret)
        assertEquals(old.token.name, new.token.name)
        assertEquals(old.token.scopes, new.token.scopes)
        assertEquals(clock.now + 10.days, new.token.expiresAt)
        assertEquals(clock.now, tokens.findBySecretHash("h(osp_secret1)")?.revokedAt)
        assertEquals(mapOf("replacement" to new.token.id.toString()), audit.entries.last().details)
    }

    @Test
    fun aTokenWithoutExpiryRotatesWithoutExpiry() = runTest {
        val old = create(userId, "t", listOf("orgs:read"), null)

        assertNull(rotate(userId, old.token.id).token.expiresAt)
    }

    @Test
    fun onlyALiveUserTokenRotates() = runTest {
        val revoked = create(userId, "r", listOf("orgs:read"), null).also { revoke(userId, it.token.id) }
        val expired = create(userId, "e", listOf("orgs:read"), 1.days)
        val agent = issueAgent(userId, Uuid.random(), "run-1", 1.hours)
        val raced = create(userId, "x", listOf("orgs:read"), null)
        clock.now += 1.days

        assertFailsWith<TokenNotFoundException> { rotate(userId, Uuid.random()) }
        assertFailsWith<TokenNotFoundException> { rotate(userId, revoked.token.id) }
        assertFailsWith<TokenNotFoundException> { rotate(userId, expired.token.id) }
        assertFailsWith<TokenNotFoundException> { rotate(userId, agent.token.id) }
        tokens.failRotation = true
        assertFailsWith<TokenNotFoundException> { rotate(userId, raced.token.id) }
    }

    @Test
    fun anAgentTokenHasOnlyTheAgentScopeAndItsSession() = runTest {
        val orgId = Uuid.random()
        val issued = issueAgent(userId, orgId, "run-1", 2.hours)

        assertEquals(TokenKind.AGENT, issued.token.kind)
        assertEquals("osa_secret1", issued.secret)
        assertEquals(setOf(AGENT_SCOPE), issued.token.scopes)
        assertEquals("run-1", issued.token.agentSessionId)
        assertEquals(orgId, issued.token.orgId)
        assertEquals(clock.now + 2.hours, issued.token.expiresAt)
        assertFailsWith<TokenNotFoundException>("An agent token is not revoked from the console") {
            revoke(userId, issued.token.id)
        }
    }

    @Test
    fun anAgentTokenNeedsASessionAndAShortLifetime() = runTest {
        assertFailsWith<IllegalArgumentException> { issueAgent(userId, Uuid.random(), " ", 1.hours) }
        assertFailsWith<IllegalArgumentException> { issueAgent(userId, Uuid.random(), "x".repeat(129), 1.hours) }
        assertFailsWith<IllegalArgumentException> { issueAgent(userId, Uuid.random(), "run", 0.hours) }
        assertFailsWith<IllegalArgumentException> { issueAgent(userId, Uuid.random(), "run", 25.hours) }
    }

    @Test
    fun endingAnAgentSessionRevokesItsTokens() = runTest {
        issueAgent(userId, Uuid.random(), "run-1", 1.hours)
        issueAgent(userId, Uuid.random(), "run-1", 1.hours)
        issueAgent(userId, Uuid.random(), "run-2", 1.hours)

        assertEquals(2, RevokeAgentTokensUseCaseImpl(tokens, clock)("run-1"))
        assertEquals(0, RevokeAgentTokensUseCaseImpl(tokens, clock)("run-1"))
    }
}
