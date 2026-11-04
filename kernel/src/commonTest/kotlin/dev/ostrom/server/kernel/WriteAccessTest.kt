// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.governs
import dev.ostrom.server.kernel.auth.mayWrite
import dev.ostrom.server.kernel.auth.requireWrite
import dev.ostrom.server.kernel.errors.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SECURITY-AUDIT S2-52: a console write takes a person's session; a token only where the route declares it. */
class WriteAccessTest {
    private fun principal(kind: PrincipalKind, channel: PrincipalChannel = PrincipalChannel.API_TOKEN) =
        AuthenticatedPrincipal("someone", kind, setOf("user:*", "secrets:write"), channel = channel)

    private val browser = principal(PrincipalKind.USER, PrincipalChannel.BROWSER)
    private val app = principal(PrincipalKind.USER, PrincipalChannel.MOBILE_APP)
    private val personal = principal(PrincipalKind.USER)
    private val agent = principal(PrincipalKind.SERVICE)
    private val device = principal(PrincipalKind.DEVICE)

    @Test
    fun aPrincipalIsAnApiTokenUnlessTheIdentityModuleSaysOtherwise() {
        val principal = AuthenticatedPrincipal("someone", PrincipalKind.USER, emptySet())
        assertEquals(PrincipalChannel.API_TOKEN, principal.channel)
    }

    @Test
    fun aConsoleWriteAdmitsOnlyABrowserSession() {
        assertTrue(browser.mayWrite(WriteAccess.BROWSER))
        for (other in listOf(app, personal, agent, device)) {
            assertFalse(other.mayWrite(WriteAccess.BROWSER), "$other")
        }
    }

    @Test
    fun aBrowserChannelThatIsNotAPersonIsRefused() {
        assertFalse(principal(PrincipalKind.SERVICE, PrincipalChannel.BROWSER).mayWrite(WriteAccess.PERSON))
        assertFalse(principal(PrincipalKind.DEVICE, PrincipalChannel.MOBILE_APP).mayWrite(WriteAccess.PERSON))
    }

    @Test
    fun aPersonsWriteAlsoAdmitsTheUnlockedApp() {
        assertTrue(browser.mayWrite(WriteAccess.PERSON))
        assertTrue(app.mayWrite(WriteAccess.PERSON))
        for (other in listOf(personal, agent, device)) {
            assertFalse(other.mayWrite(WriteAccess.PERSON), "$other")
        }
    }

    @Test
    fun aRouteForProgramsAdmitsOnlyTheTokenKindsItDeclares() {
        val access = WriteAccess.tokens(PrincipalKind.USER, PrincipalKind.SERVICE)
        assertTrue(browser.mayWrite(access))
        assertTrue(personal.mayWrite(access))
        assertTrue(agent.mayWrite(access))
        assertFalse(device.mayWrite(access))
        assertFalse(app.mayWrite(access))
    }

    @Test
    fun aGovernanceWriteAdmitsTheBrowserAndAPersonsTokenThatHoldsItsScope() {
        val access = WriteAccess.governance(GovernanceScopes.POLICY_WRITE, PrincipalKind.SERVICE)
        val elevated = AuthenticatedPrincipal("ada", PrincipalKind.USER, GovernanceScopes.ALL)
        assertTrue(browser.mayWrite(access))
        assertTrue(elevated.mayWrite(access), "The token of ostrom login --admin")
        assertTrue(agent.mayWrite(access), "A declared kind")
        assertFalse(personal.mayWrite(access), "A person's token without the scope")
        assertFalse(app.mayWrite(access))
        val agentWithTheScope = AuthenticatedPrincipal("s", PrincipalKind.AGENT, GovernanceScopes.ALL)
        assertFalse(agentWithTheScope.mayWrite(access), "Only a person governs")
        assertFalse(elevated.mayWrite(WriteAccess.BROWSER), "A console write that declares no governance scope")
    }

    @Test
    fun onlyAPersonsBrowserSessionOrTheirElevatedTokenGoverns() {
        val scope = GovernanceScopes.HARNESS_WRITE
        fun holding(vararg scopes: String, channel: PrincipalChannel = PrincipalChannel.API_TOKEN) =
            AuthenticatedPrincipal("ada", PrincipalKind.USER, scopes.toSet(), channel = channel)
        assertTrue(browser.governs(scope))
        assertTrue(holding(scope).governs(scope))
        assertFalse(holding(GovernanceScopes.POLICY_WRITE).governs(scope))
        assertFalse(personal.governs(scope), "user:* on a token is never a browser session's")
        assertFalse(holding(scope, channel = PrincipalChannel.MOBILE_APP).governs(scope))
        assertFalse(holding(scope, channel = PrincipalChannel.BROWSER).governs(scope), "A session is user:* or nothing")
        assertFalse(AuthenticatedPrincipal("box", PrincipalKind.DEVICE, setOf(scope)).governs(scope))
        assertFalse(principal(PrincipalKind.SERVICE, PrincipalChannel.BROWSER).governs(scope))
        assertEquals(setOf("policy:write", "harness:write", "gates:policy"), GovernanceScopes.ALL)
    }

    @Test
    fun requireWriteRefusesWithAForbiddenAndDefaultsToTheBrowser() {
        browser.requireWrite()
        app.requireWrite(WriteAccess.PERSON)
        val refused = assertFailsWith<BrowserSessionRequiredException> { personal.requireWrite() }
        assertEquals(ErrorKind.FORBIDDEN, refused.kind)
        assertFailsWith<BrowserSessionRequiredException> { app.requireWrite() }
    }
}
