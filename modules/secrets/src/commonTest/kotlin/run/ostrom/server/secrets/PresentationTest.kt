// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.InvalidSecretException
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.SecretCursor
import run.ostrom.server.secrets.domain.SecretNotFoundException
import run.ostrom.server.secrets.presentation.SecretWriteRequest
import run.ostrom.server.secrets.presentation.forKey
import run.ostrom.server.secrets.presentation.personalScope
import run.ostrom.server.secrets.presentation.toIdOr
import run.ostrom.server.secrets.presentation.toSecretCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PresentationTest {
    @Test
    fun theScopeIsOrgByDefaultOrPersonal() {
        assertEquals(false, personalScope(null))
        assertEquals(false, personalScope("org"))
        assertEquals(true, personalScope("personal"))
        assertFailsWith<InvalidSecretException> { personalScope("everyone") }
    }

    @Test
    fun aServiceKeySeesTheOrganizationAsAnAdminOnlyOnTheReadsTeamReadOpens() {
        val member = Caller(Uuid.random(), Uuid.random(), MemberRole.MEMBER)
        fun principal(kind: PrincipalKind, vararg scopes: String) = AuthenticatedPrincipal("s", kind, scopes.toSet())
        val teamKey = principal(PrincipalKind.SERVICE, "team:read", "usage:read")
        assertEquals(member.copy(role = MemberRole.ADMIN), member.forKey(teamKey, teamRead = true))
        assertEquals(member, member.forKey(teamKey), "Any other route: the plain member the resolver gave")
        assertEquals(member, member.forKey(principal(PrincipalKind.SERVICE, "usage:read"), teamRead = true))
        assertEquals(member, member.forKey(principal(PrincipalKind.USER, "team:read"), teamRead = true))
    }

    @Test
    fun aMalformedIdIsTheSameAsAnUnknownOne() {
        val id = Uuid.random()
        assertEquals(id, id.toString().toIdOr(::SecretNotFoundException))
        assertFailsWith<SecretNotFoundException> { "not-an-id".toIdOr(::SecretNotFoundException) }
    }

    @Test
    fun aWriteRequestNeverPrintsItsValue() {
        val printed = SecretWriteRequest("TOKEN", "glsa_very_secret_value", "org").toString()
        assertFalse("glsa_very_secret_value" in printed, printed)
        assertTrue("TOKEN" in printed)
    }

    @Test
    fun aCursorReadsBackTheNameAndIdOfTheLastSecret() {
        val id = Uuid.random()
        assertEquals(SecretCursor("A.b-c", id), "A.b-c/$id".toSecretCursor())
        for (bad in listOf("A", "A/x", "")) assertFailsWith<InvalidSecretException>(bad) { bad.toSecretCursor() }
    }
}
