// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.AGENT_SCOPE
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.WebSession
import run.ostrom.server.identity.domain.isBrowserSession
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.RejectingTokenAuthenticator
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
import kotlin.uuid.Uuid

private const val CONSOLE = "https://app.ostrom.run"

class CredentialAuthenticatorTest {
    private val tokens = InMemoryApiTokensRepository()
    private val sessions = InMemoryWebSessionsRepository()
    private val clock = SettableClock()
    private val authenticator = CredentialAuthenticatorImpl(tokens, sessions, FakeHasher(), clock, setOf(CONSOLE), "")
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
                personalTokenName = "t",
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
        assertEquals(null, principal?.personalTokenName, "Only a personal token is named")
    }

    @Test
    fun aPersonalTokenNamedBeforeTheRuleIsShownWithoutWhatBreaksALineOrHidesText() = runTest {
        val legacy = token("osp_old", TokenKind.PERSONAL).copy(name = "ostrom login\nOwner\u202E  approval\u200B")
        tokens.tokens["h(osp_old)"] = legacy
        assertEquals("ostrom login Owner approval", authenticator.authenticate("osp_old")?.personalTokenName)
        tokens.tokens["h(osp_old)"] = legacy.copy(name = "\u200B\n")
        assertEquals("unnamed token", authenticator.authenticate("osp_old")?.personalTokenName)
    }

    @Test
    fun anAgentTokenWithoutOrganizationHasNoneAndOneWithoutSessionIsACorruptRow() = runTest {
        tokens.tokens["h(osa_c)"] = agentToken("osa_c", setOf(AGENT_SCOPE), session = "run-3").copy(orgId = null)
        val corrupt = agentToken("osa_d", setOf(AGENT_SCOPE), session = "run-4")
        tokens.tokens["h(osa_d)"] = corrupt.copy(agentSessionId = null)

        assertEquals(null, authenticator.authenticate("osa_c")?.orgId)
        assertFailsWith<IllegalStateException> { authenticator.authenticate("osa_d") }
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
            AuthenticatedPrincipal(
                userId.toString(), PrincipalKind.USER, setOf(ALL_USER_SCOPES), stored.id.toString(),
                channel = PrincipalChannel.BROWSER, authenticatedAt = stored.createdAt,
            ),
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
    fun aYesNeedsARecentSignInWhichOnlyABrowserSessionCarriesAndSigningInAgainRenews() = runTest {
        val signedInAt = clock.now
        session("oss_old")
        token("osp_p", TokenKind.PERSONAL, setOf("profile:read"))
        clock.now += 6.minutes

        // Six minutes after signing in: the step-up of a yes (five minutes, S2-54) refuses this session.
        val old = authenticator.authenticateSession("oss_old", null, requireCsrf = false)
        assertEquals(signedInAt, old?.authenticatedAt)
        assertEquals(PrincipalChannel.BROWSER, old?.channel)
        // A token proves no sign-in at all, whatever its scopes.
        val api = authenticator.authenticate("osp_p")
        assertNull(api?.authenticatedAt)
        assertEquals(PrincipalChannel.API_TOKEN, api?.channel)
        // Signing in again starts a new session: its sign-in is now.
        session("oss_new")
        val renewed = authenticator.authenticateSession("oss_new", null, requireCsrf = false)
        assertEquals(clock.now, renewed?.authenticatedAt)
    }

    @Test
    fun aStateChangingRequestNeedsTheSessionsCsrfToken() = runTest {
        session("oss_a")

        assertNull(authenticator.authenticateSession("oss_a", null, requireCsrf = true, CONSOLE))
        assertNull(authenticator.authenticateSession("oss_a", "csrf(oss_b)", requireCsrf = true, CONSOLE))
        assertNull(authenticator.authenticateSession("oss_a", "short", requireCsrf = true, CONSOLE))
        val principal = authenticator.authenticateSession("oss_a", "csrf(oss_a)", requireCsrf = true, CONSOLE)
        assertEquals(userId.toString(), principal?.subjectId)
    }

    @Test
    fun aStateChangingRequestFromAnotherOriginOrWithoutOneIsRefused() = runTest {
        session("oss_a")

        // S2-08: the right CSRF token is not enough, the request must come from the console.
        val evil = "https://evil.test"
        assertNull(authenticator.authenticateSession("oss_a", "csrf(oss_a)", requireCsrf = true, evil))
        assertNull(authenticator.authenticateSession("oss_a", "csrf(oss_a)", requireCsrf = true, null))
        assertNull(authenticator.authenticateSession("oss_a", "csrf(oss_a)", requireCsrf = true))
        // A read needs neither.
        assertNotNull(authenticator.authenticateSession("oss_a", null, requireCsrf = false, evil))
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
    fun aServiceKeyActsAsItselfInItsOrganizationNeverAsItsCreator() = runTest {
        val stored = setOf("rules:read", "gates:answer", "orgs:write")
        val key = token("oss_k", TokenKind.SERVICE, stored, expiresIn = null, org = orgId)
        val corrupt = token("oss_x", TokenKind.SERVICE, org = null)

        assertEquals(
            AuthenticatedPrincipal(
                key.id.toString(), PrincipalKind.SERVICE, setOf("rules:read"), key.id.toString(), orgId.toString(),
            ),
            authenticator.authenticate("oss_k"),
            "Only what a CI calls, never a gate's yes, whatever its row says (S2-04)",
        )
        assertEquals(null, authenticator.authenticate("oss_x")?.orgId, "A row without organization binds to none")
        assertEquals(corrupt.id.toString(), authenticator.authenticate("oss_x")?.subjectId)
        assertNull(authenticator.authenticate("oss_x")?.onBehalfOf, "A row without a scope of the catalogue: nobody")
    }

    @Test
    fun aServiceKeyOfMemberScopesActsAsItsCreatorWithinThemAndAMixedRowActsAsNobody() = runTest {
        val member = token("oss_m", TokenKind.SERVICE, setOf("inference:use", "team:read", "billing:read"), org = orgId)
        token("oss_mixed", TokenKind.SERVICE, setOf("rules:read", "inference:use"), org = orgId)

        assertEquals(
            AuthenticatedPrincipal(
                member.id.toString(), PrincipalKind.SERVICE, setOf("inference:use", "team:read"), member.id.toString(),
                orgId.toString(), onBehalfOf = member.userId.toString(),
            ),
            authenticator.authenticate("oss_m"),
            "Its creator, within the catalogue; never what a service key may not hold",
        )
        assertNull(authenticator.authenticate("oss_mixed")?.onBehalfOf, "A row mixing both never acts as a person")
        assertEquals(setOf("rules:read", "inference:use"), authenticator.authenticate("oss_mixed")?.scopes)
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

    @Test
    fun aCredentialIsLiveWhileNeitherRevokedNorExpired() = runTest {
        val user = userId.toString()
        val open = session("oss_open")
        val closed = session("oss_closed", revoked = true)
        val over = session("oss_over", expired = true)
        val app = token("osm_app", TokenKind.MOBILE)
        val forever = token("osp_forever", TokenKind.PERSONAL, expiresIn = null)
        val ended = token("osp_ended", TokenKind.PERSONAL).copy(revokedAt = clock.now)
        tokens.tokens["h(osp_ended)"] = ended
        assertTrue(authenticator.isLive(user, open.id.toString()))
        assertTrue(authenticator.isLive(user, app.id.toString()))
        assertTrue(authenticator.isLive(user, forever.id.toString()))
        assertFalse(authenticator.isLive(user, closed.id.toString()))
        assertFalse(authenticator.isLive(user, over.id.toString()))
        assertFalse(authenticator.isLive(user, ended.id.toString()))
        assertFalse(authenticator.isLive(user, Uuid.random().toString()))
        assertFalse(authenticator.isLive(Uuid.random().toString(), open.id.toString()), "Another person's")
        assertFalse(authenticator.isLive("not-a-user", open.id.toString()))
        assertFalse(authenticator.isLive(user, "not-a-credential"))
        clock.now += 11.days
        assertFalse(authenticator.isLive(user, app.id.toString()), "Expired")
        assertFalse(RejectingTokenAuthenticator().isLive(user, open.id.toString()), "Nothing is live by default")
    }
}
