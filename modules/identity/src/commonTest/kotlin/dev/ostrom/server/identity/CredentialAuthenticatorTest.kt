// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AGENT_SCOPE
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.WebSession
import dev.ostrom.server.identity.domain.isBrowserSession
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.kernel.auth.ALL_USER_SCOPES
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class CredentialAuthenticatorTest {
    private val tokens = InMemoryApiTokensRepository()
    private val sessions = InMemoryWebSessionsRepository()
    private val clock = SettableClock()
    private val authenticator = CredentialAuthenticatorImpl(tokens, sessions, FakeHasher(), clock)
    private val userId = Uuid.parse("00000000-0000-4000-8000-0000000000aa")
    private val orgId = Uuid.parse("00000000-0000-4000-8000-0000000000bb")

    private fun token(
        secret: String,
        kind: TokenKind,
        scopes: Set<String> = setOf("orgs:read"),
        expiresIn: kotlin.time.Duration? = 10.days,
        org: Uuid? = null,
    ): ApiToken {
        val token = ApiToken(
            Uuid.random(), userId, org, "t", kind, scopes, secret.take(12), null, clock.now,
            expiresIn?.let { clock.now + it }, null, null,
        )
        tokens.tokens["h($secret)"] = token
        return token
    }

    private fun agentToken(secret: String, scopes: Set<String>, session: String): ApiToken {
        val agent = token(secret, TokenKind.AGENT, scopes, org = orgId).copy(agentSessionId = session)
        tokens.tokens["h($secret)"] = agent
        return agent
    }

    private fun revokedToken(secret: String, kind: TokenKind) {
        val revoked = token(secret, kind).copy(revokedAt = clock.now)
        tokens.tokens["h($secret)"] = revoked
    }

    private fun session(secret: String, revoked: Boolean = false, expired: Boolean = false): WebSession {
        val session = WebSession(
            Uuid.random(), userId, clock.now, if (expired) clock.now else clock.now + 30.days, clock.now,
            if (revoked) clock.now else null,
        )
        sessions.sessions["h($secret)"] = session
        return session
    }

    @Test
    fun aPersonalTokenActsAsItsUserWithItsScopes() = runTest {
        val stored = token("osp_a", TokenKind.PERSONAL)

        assertEquals(
            AuthenticatedPrincipal(
                userId.toString(), PrincipalKind.USER, setOf("orgs:read"), stored.id.toString(), null,
            ),
            authenticator.authenticate("osp_a"),
        )
    }

    @Test
    fun aCliTokenActsAsItsUser() = runTest {
        token("osc_a", TokenKind.CLI, expiresIn = null, org = orgId)

        val principal = authenticator.authenticate("osc_a")
        assertEquals(PrincipalKind.USER, principal?.kind)
        assertEquals(orgId.toString(), principal?.orgId)
    }

    @Test
    fun anAgentTokenIsConfinedToItsSessionAndNeverPassesAConsoleScope() = runTest {
        val stored = agentToken("osa_a", setOf(AGENT_SCOPE), session = "run-1")

        val principal = authenticator.authenticate("osa_a")
        assertEquals(
            AuthenticatedPrincipal(
                "run-1", PrincipalKind.AGENT, setOf(AGENT_SCOPE), stored.id.toString(), orgId.toString(),
            ),
            principal,
        )
        assertFalse(principal!!.allows("orgs:write"))
        assertTrue(principal.allows(AGENT_SCOPE))
        assertFalse(principal.isBrowserSession)
    }

    @Test
    fun unknownRevokedExpiredMisprefixedOrOversizedTokensAreRefused() = runTest {
        revokedToken("osp_revoked", TokenKind.PERSONAL)
        token("osp_expired", TokenKind.PERSONAL, expiresIn = 0.days)
        token("osc_wrongkind", TokenKind.PERSONAL)

        assertNull(authenticator.authenticate("osp_unknown"))
        assertNull(authenticator.authenticate("osp_revoked"))
        assertNull(authenticator.authenticate("osp_expired"))
        assertNull(authenticator.authenticate("osc_wrongkind"))
        assertNull(authenticator.authenticate("ghp_github"))
        assertNull(authenticator.authenticate("osp_" + "x".repeat(200)))
    }

    @Test
    fun aSessionSecretIsNeverABearerToken() = runTest {
        session("oss_a")

        assertNull(authenticator.authenticate("oss_a"))
    }

    @Test
    fun theLastUseIsWrittenAtMostEveryFiveMinutes() = runTest {
        token("osp_a", TokenKind.PERSONAL)

        authenticator.authenticate("osp_a")
        authenticator.authenticate("osp_a")
        clock.now += 4.minutes
        authenticator.authenticate("osp_a")
        assertEquals(1, tokens.touches)
        clock.now += 2.minutes
        authenticator.authenticate("osp_a")
        assertEquals(2, tokens.touches)
    }

    @Test
    fun aBrowserSessionActsWithAllTheUsersRights() = runTest {
        val stored = session("oss_a")

        val principal = authenticator.authenticateSession("oss_a", null, requireCsrf = false)
        assertEquals(
            AuthenticatedPrincipal(userId.toString(), PrincipalKind.USER, setOf(ALL_USER_SCOPES), stored.id.toString()),
            principal,
        )
        assertTrue(principal!!.isBrowserSession)
        assertTrue(principal.allows("orgs:write"))
        assertEquals(0, sessions.touches, "Just created: no write")
        clock.now += 6.minutes
        authenticator.authenticateSession("oss_a", null, requireCsrf = false)
        assertEquals(1, sessions.touches)
    }

    @Test
    fun aStateChangingRequestNeedsTheSessionsCsrfToken() = runTest {
        session("oss_a")

        assertNull(authenticator.authenticateSession("oss_a", null, requireCsrf = true))
        assertNull(authenticator.authenticateSession("oss_a", "csrf(oss_b)", requireCsrf = true))
        assertNull(authenticator.authenticateSession("oss_a", "short", requireCsrf = true))
        assertEquals(
            userId.toString(),
            authenticator.authenticateSession("oss_a", "csrf(oss_a)", requireCsrf = true)?.subjectId,
        )
    }

    @Test
    fun unknownRevokedExpiredOrForeignCookiesAreRefused() = runTest {
        session("oss_revoked", revoked = true)
        session("oss_expired", expired = true)
        token("osp_a", TokenKind.PERSONAL)

        assertNull(authenticator.authenticateSession("oss_unknown", null, false))
        assertNull(authenticator.authenticateSession("oss_revoked", null, false))
        assertNull(authenticator.authenticateSession("oss_expired", null, false))
        assertNull(authenticator.authenticateSession("osp_a", null, false))
        assertNull(authenticator.authenticateSession("oss_" + "x".repeat(200), null, false))
    }

    @Test
    fun aServicePrincipalOnlyPassesItsScopes() {
        val service = AuthenticatedPrincipal("svc", PrincipalKind.SERVICE, setOf("checks:write"))

        assertTrue(service.allows("checks:write"))
        assertFalse(service.allows("orgs:read"))
        assertFalse(AuthenticatedPrincipal("a", PrincipalKind.AGENT, setOf(ALL_USER_SCOPES)).allows("orgs:read"))
        assertFalse(AuthenticatedPrincipal("u", PrincipalKind.USER, emptySet()).allows("orgs:read"))
        assertFalse(AuthenticatedPrincipal("u", PrincipalKind.USER, setOf("orgs:read")).allows("orgs:write"))
    }

    @Test
    fun aCliTokenNeverAnswersAGateNorEditsAWorkflowEvenIfItsRowSaysSo() = runTest {
        // S2-04 / S2-31: a CLI token issued before the fix, or a row edited by hand, still cannot say yes.
        val everything = setOf("gates:answer", "workflows:write", "sessions:write", ALL_USER_SCOPES)
        token("osc_old", TokenKind.CLI, scopes = everything)

        val principal = assertNotNull(authenticator.authenticate("osc_old"))
        assertEquals(setOf("sessions:write"), principal.scopes)
        assertFalse(principal.allows("gates:answer"))
        assertFalse(principal.allows("workflows:write"))
        assertFalse(principal.isBrowserSession)
    }

    @Test
    fun aPersonalTokenNeverAnswersAGateEvenIfItsRowSaysSo() = runTest {
        val everything = setOf("gates:answer", "workflows:write", AGENT_SCOPE, ALL_USER_SCOPES)
        token("osp_old", TokenKind.PERSONAL, scopes = everything)

        val principal = assertNotNull(authenticator.authenticate("osp_old"))
        assertEquals(setOf("workflows:write"), principal.scopes)
        assertFalse(principal.allows("gates:answer"))
        assertFalse(principal.isBrowserSession)
    }

    @Test
    fun anAgentTokenOnlyHoldsItsSessionScopeWhateverItsRowSays() = runTest {
        agentToken("osa_b", setOf("gates:answer", AGENT_SCOPE), session = "run-2")

        assertEquals(setOf(AGENT_SCOPE), authenticator.authenticate("osa_b")?.scopes)
    }
}
