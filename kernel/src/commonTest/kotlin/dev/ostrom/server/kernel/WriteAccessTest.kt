// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.WriteAccess
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
        AuthenticatedPrincipal("someone", kind, setOf("user:*", "secrets:write"), channel)

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
    fun requireWriteRefusesWithAForbiddenAndDefaultsToTheBrowser() {
        browser.requireWrite()
        app.requireWrite(WriteAccess.PERSON)
        val refused = assertFailsWith<BrowserSessionRequiredException> { personal.requireWrite() }
        assertEquals(ErrorKind.FORBIDDEN, refused.kind)
        assertFailsWith<BrowserSessionRequiredException> { app.requireWrite() }
    }
}
