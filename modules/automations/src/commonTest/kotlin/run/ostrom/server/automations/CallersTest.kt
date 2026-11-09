// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.MissingWaitScopeException
import run.ostrom.server.automations.domain.NoOrganizationException
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitNotFoundException
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.FakeWaitAgents
import run.ostrom.server.automations.fakes.LAUNCH
import run.ostrom.server.automations.fakes.LAUNCH_SESSION
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.TestOrgResolver
import run.ostrom.server.automations.infrastructure.NoWaitAgents
import run.ostrom.server.automations.presentation.WAITS_ASK
import run.ostrom.server.automations.presentation.WAITS_READ
import run.ostrom.server.automations.presentation.WAITS_WRITE
import run.ostrom.server.automations.presentation.agentWaitCallerOf
import run.ostrom.server.automations.presentation.allowsWaits
import run.ostrom.server.automations.presentation.waitCallerOf
import run.ostrom.server.automations.presentation.waitId
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.NoOrganizationCredentialException
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CallersTest {
    private fun principal(kind: PrincipalKind, vararg scopes: String, subject: String = "alice") =
        AuthenticatedPrincipal(subject, kind, scopes.toSet())

    @Test
    fun scopesNest() {
        val login = principal(PrincipalKind.USER, "user:*")
        listOf(WAITS_READ, WAITS_ASK, WAITS_WRITE).forEach { assertTrue(login.allowsWaits(it)) }
        val device = principal(PrincipalKind.DEVICE, "user:*")
        assertFalse(device.allowsWaits(WAITS_READ))
        val writer = principal(PrincipalKind.SERVICE, WAITS_WRITE)
        listOf(WAITS_READ, WAITS_ASK, WAITS_WRITE).forEach { assertTrue(writer.allowsWaits(it)) }
        val agent = principal(PrincipalKind.DEVICE, WAITS_ASK)
        assertTrue(agent.allowsWaits(WAITS_READ))
        assertTrue(agent.allowsWaits(WAITS_ASK))
        assertFalse(agent.allowsWaits(WAITS_WRITE))
        val reader = principal(PrincipalKind.SERVICE, WAITS_READ)
        assertTrue(reader.allowsWaits(WAITS_READ))
        assertFalse(reader.allowsWaits(WAITS_ASK))
        assertFalse(principal(PrincipalKind.USER).allowsWaits(WAITS_READ))
    }

    @Test
    fun theCallerIsTheMemberTheCredentialActsFor() = runTest {
        val login = TestOrgResolver.waitCallerOf(principal(PrincipalKind.USER, "user:*"), WAITS_WRITE)
        assertEquals(ORG_A, login.orgId)
        assertEquals(ALICE.toString(), login.memberId)
        assertFalse(login.admin)
        assertTrue(login.interactive)
        val key = principal(PrincipalKind.SERVICE, WAITS_READ, subject = "dave")
        val dave = TestOrgResolver.waitCallerOf(key, WAITS_READ)
        assertTrue(dave.admin)
        assertFalse(dave.interactive)
        assertFalse(dave.actsAsAdmin)
        val carol = TestOrgResolver.waitCallerOf(principal(PrincipalKind.USER, "user:*", subject = "carol"), WAITS_READ)
        assertTrue(carol.actsAsAdmin)
        assertFailsWith<MissingWaitScopeException> {
            TestOrgResolver.waitCallerOf(principal(PrincipalKind.SERVICE, WAITS_READ), WAITS_ASK)
        }
        assertFailsWith<NoOrganizationException> {
            TestOrgResolver.waitCallerOf(principal(PrincipalKind.USER, "user:*", subject = "nobody"), WAITS_READ)
        }
    }

    @Test
    fun anAgentTokenNeverPassesTheWaitsScopesWhateverItLists() {
        val agent = principal(PrincipalKind.AGENT, WAITS_WRITE, "user:*", subject = LAUNCH)
        listOf(WAITS_READ, WAITS_ASK, WAITS_WRITE).forEach { assertFalse(agent.allowsWaits(it), it) }
    }

    @Test
    fun anAgentTokenAsksAndReadsAsItsPersonNeverAsAnAdministrator() = runTest {
        val agent = principal(PrincipalKind.AGENT, "agent:session", subject = LAUNCH)
        val expected = WaitCaller(
            ORG_A,
            BOB.toString(),
            admin = false,
            interactive = false,
            agent = true,
            session = LAUNCH_SESSION,
            segments = listOf(LAUNCH_SESSION),
        )
        assertEquals(expected, FakeWaitAgents.agentWaitCallerOf(agent, LAUNCH_SESSION, WAITS_ASK))
        assertEquals(expected, FakeWaitAgents.agentWaitCallerOf(agent, LAUNCH_SESSION, WAITS_READ))
        assertFailsWith<MissingWaitScopeException>("An agent never answers") {
            FakeWaitAgents.agentWaitCallerOf(agent, LAUNCH_SESSION, WAITS_WRITE)
        }
        assertFailsWith<NoOrganizationCredentialException> {
            NoWaitAgents.agentWaitCallerOf(agent, LAUNCH_SESSION, WAITS_ASK)
        }
        assertNull(NoWaitAgents.resolve(agent, LAUNCH_SESSION), "Fails closed until the launches module binds one")
    }

    @Test
    fun aMalformedIdNamesNoWait() {
        val id = Uuid.random()
        assertEquals(id, waitId(id.toString()))
        assertFailsWith<WaitNotFoundException> { waitId("nope") }
    }
}
