// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ADMIN_HANDOVER_SCOPE
import run.ostrom.server.identity.domain.ADMIN_READ_SCOPES
import run.ostrom.server.identity.domain.ADMIN_SCOPES
import run.ostrom.server.identity.domain.ADMIN_TOKEN_LIFETIME
import run.ostrom.server.identity.domain.ADMIN_TOKEN_SCOPES
import run.ostrom.server.identity.domain.AuditLogGovernanceAudit
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.InvalidScopesException
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.grantedScopes
import run.ostrom.server.identity.domain.validateScopes
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The governance scopes of `ostrom login --admin`: only its token holds them, and each of its writes is recorded. */
class AdminScopesTest {
    @Test
    fun onlyTheAdminTokenHoldsTheGovernanceScopesWhateverARowLists() {
        val stored = ADMIN_SCOPES + CLI_SCOPES + "orgs:write" + "user:*"
        // never more than its row lists: this one lists no read of the governance
        assertEquals(CLI_SCOPES + ADMIN_SCOPES, grantedScopes(TokenKind.ADMIN, stored))
        assertEquals(CLI_SCOPES + ADMIN_SCOPES + ADMIN_READ_SCOPES + ADMIN_HANDOVER_SCOPE, ADMIN_TOKEN_SCOPES)
        assertEquals(CLI_SCOPES + "orgs:write", grantedScopes(TokenKind.PERSONAL, stored - "user:*"))
        assertEquals(CLI_SCOPES, grantedScopes(TokenKind.CLI, stored))
        assertEquals(8.hours, ADMIN_TOKEN_LIFETIME)
        assertEquals("osg", TokenKind.ADMIN.prefix)
    }

    @Test
    fun theAdminTokenReadsTheModelPolicyItRewrites() {
        // ostrom policy set models keeps the default and the members' lists: it reads the policy before writing it
        assertTrue("inference:read" in ADMIN_TOKEN_SCOPES, "$ADMIN_TOKEN_SCOPES")
        assertTrue("inference:read" in grantedScopes(TokenKind.ADMIN, ADMIN_TOKEN_SCOPES))
        // a read, not a governance scope: a personal token still holds it, the device's token still does not
        assertEquals(setOf("inference:read"), grantedScopes(TokenKind.PERSONAL, setOf("inference:read")))
        assertTrue("inference:read" !in grantedScopes(TokenKind.CLI, ADMIN_TOKEN_SCOPES))
        // reads only, which the browser does not show: never a write slipped in unseen
        assertTrue(ADMIN_READ_SCOPES.all { it.endsWith(":read") }, "$ADMIN_READ_SCOPES")
        assertTrue((ADMIN_READ_SCOPES intersect ADMIN_SCOPES).isEmpty())
    }

    @Test
    fun theAdminTokenHandsAnImportedAccountOverAsTeamCodesCli() {
        // ostrom accounts handover finds the account by its name, then hands it over (TeamCode's accounts handover)
        val granted = grantedScopes(TokenKind.ADMIN, ADMIN_TOKEN_SCOPES)
        assertTrue(setOf("accounts:read", "accounts:write").all { it in granted }, "$granted")
        // the device's token of a plain `ostrom login` never does
        val cli = grantedScopes(TokenKind.CLI, ADMIN_TOKEN_SCOPES)
        assertTrue("accounts:read" !in cli && "accounts:write" !in cli, "$cli")
        assertTrue(ADMIN_HANDOVER_SCOPE !in ADMIN_SCOPES, "Not a governance scope: the browser approves those")
    }

    @Test
    fun aPersonalTokenCannotAskForAGovernanceScope() {
        for (scope in ADMIN_SCOPES) {
            assertFailsWith<InvalidScopesException>(scope) { validateScopes(listOf("orgs:read", scope)) }
        }
    }

    @Test
    fun eachGovernanceWriteIsAnEventOfTheTokensOrganizationByItsPerson() = runTest {
        val audit = RecordingAuditLog()
        val governance = AuditLogGovernanceAudit(audit)
        val person = Uuid.random()
        val orgId = Uuid.random()
        val token = Uuid.random().toString()
        val principal =
            AuthenticatedPrincipal(person.toString(), PrincipalKind.USER, ADMIN_SCOPES, token, orgId.toString())

        governance.record(principal, "policy:write", "PATCH /api/v1/rules/r1")
        val unboundPrincipal = principal.copy(credentialId = null, orgId = null)
        governance.record(unboundPrincipal, "harness:write", "DELETE /api/v1/workflows/w")

        val (bound, unbound) = audit.entries
        assertEquals(orgId, bound.orgId)
        assertEquals(person, bound.actorId)
        assertEquals("governance.write", bound.action)
        assertEquals("route", bound.targetType)
        assertEquals("PATCH /api/v1/rules/r1", bound.targetId)
        assertEquals(mapOf("scope" to "policy:write", "token" to token), bound.details)
        assertNull(unbound.orgId)
        assertEquals(mapOf("scope" to "harness:write", "token" to ""), unbound.details)
    }
}
