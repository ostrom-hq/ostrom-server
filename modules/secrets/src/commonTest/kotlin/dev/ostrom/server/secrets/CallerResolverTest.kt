// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import dev.ostrom.server.secrets.domain.UnresolvedCallerResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CallerResolverTest {
    private val principal = AuthenticatedPrincipal("u1", PrincipalKind.USER, emptySet())
    private val org = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val member = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")

    private fun resolverFor(role: OrgRole?) = OrgPrincipalCallerResolver(
        object : PrincipalOrgResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                role?.let { OrgPrincipal(orgId ?: org, member, it) }
        },
    )

    @Test
    fun ownersAndAdminsAdministerAndOtherRolesAreMembers() = runTest {
        val expected = mapOf(
            OrgRole.OWNER to MemberRole.OWNER,
            OrgRole.ADMIN to MemberRole.ADMIN,
            OrgRole.MEMBER to MemberRole.MEMBER,
            OrgRole.APPROVER to MemberRole.MEMBER,
            OrgRole.BILLING to MemberRole.MEMBER,
        )
        expected.forEach { (role, mapped) ->
            assertEquals(Caller(org, member, mapped), resolverFor(role).resolve(principal, null))
        }
    }

    @Test
    fun aPrincipalOutsideTheOrgIsNobody() = runTest {
        assertNull(resolverFor(null).resolve(principal, org))
        assertNull(UnresolvedCallerResolver().resolve(principal, org))
    }
}
