// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.AutomationNotFoundException
import dev.ostrom.server.automations.domain.MissingAutomationScopeException
import dev.ostrom.server.automations.domain.NoOrganizationException
import dev.ostrom.server.automations.fakes.ALICE
import dev.ostrom.server.automations.fakes.ORG_A
import dev.ostrom.server.automations.fakes.TestOrgResolver
import dev.ostrom.server.automations.presentation.AUTOMATIONS_READ
import dev.ostrom.server.automations.presentation.AUTOMATIONS_WRITE
import dev.ostrom.server.automations.presentation.allowsAutomations
import dev.ostrom.server.automations.presentation.automationCallerOf
import dev.ostrom.server.automations.presentation.automationId
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AutomationCallersTest {
    private fun principal(kind: PrincipalKind, vararg scopes: String, subject: String = "alice") =
        AuthenticatedPrincipal(subject, kind, scopes.toSet())

    @Test
    fun aLoginOrTheScopeOrWriteAllows() {
        val login = principal(PrincipalKind.USER, "user:*")
        assertTrue(login.allowsAutomations(AUTOMATIONS_WRITE))
        assertFalse(principal(PrincipalKind.DEVICE, "user:*").allowsAutomations(AUTOMATIONS_READ))
        assertFalse(principal(PrincipalKind.USER).allowsAutomations(AUTOMATIONS_READ))
        val reader = principal(PrincipalKind.SERVICE, AUTOMATIONS_READ)
        assertTrue(reader.allowsAutomations(AUTOMATIONS_READ))
        assertFalse(reader.allowsAutomations(AUTOMATIONS_WRITE))
        assertTrue(principal(PrincipalKind.SERVICE, AUTOMATIONS_WRITE).allowsAutomations(AUTOMATIONS_READ))
    }

    @Test
    fun theCallerIsTheMemberTheCredentialActsFor() = runTest {
        val login = TestOrgResolver.automationCallerOf(principal(PrincipalKind.USER, "user:*"), AUTOMATIONS_WRITE)
        assertEquals(ORG_A, login.orgId)
        assertEquals(ALICE.toString(), login.memberId)
        assertFalse(login.admin)
        assertTrue(login.interactive)
        val dave = TestOrgResolver.automationCallerOf(
            principal(PrincipalKind.SERVICE, AUTOMATIONS_READ, subject = "dave"),
            AUTOMATIONS_READ,
        )
        assertTrue(dave.admin)
        assertFalse(dave.interactive)
        assertFailsWith<MissingAutomationScopeException> {
            TestOrgResolver.automationCallerOf(principal(PrincipalKind.SERVICE, AUTOMATIONS_READ), AUTOMATIONS_WRITE)
        }
        assertFailsWith<NoOrganizationException> {
            val nobody = principal(PrincipalKind.USER, "user:*", subject = "nobody")
            TestOrgResolver.automationCallerOf(nobody, AUTOMATIONS_READ)
        }
    }

    @Test
    fun aMalformedIdNamesNoAutomation() {
        val id = Uuid.random()
        assertEquals(id, automationId(id.toString()))
        assertFailsWith<AutomationNotFoundException> { automationId("nope") }
    }
}
