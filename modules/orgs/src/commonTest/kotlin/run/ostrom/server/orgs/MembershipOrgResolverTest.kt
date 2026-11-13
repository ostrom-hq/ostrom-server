// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.fakes.FakeMembers
import run.ostrom.server.orgs.fakes.FakeOrgs
import run.ostrom.server.orgs.fakes.OrgsStore
import run.ostrom.server.orgs.fakes.RecordingAudit
import run.ostrom.server.orgs.infrastructure.MembershipOrgResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who a credential acts as in an organization: a person with their role, a service key as its creator and no more. */
class MembershipOrgResolverTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val store = OrgsStore()
    private val resolver = MembershipOrgResolver(FakeOrgs(store, RecordingAudit()), FakeMembers(store))
    private val acme = Uuid.random()
    private val other = Uuid.random()

    private fun join(org: Uuid, role: OrgRole): Member =
        Member(Uuid.random(), org, Uuid.random(), role, now).also { store.members[it.id] = it }

    private fun key(
        creator: Member,
        vararg scopes: String,
        bound: Uuid? = acme,
        named: String? = null,
        onBehalfOf: Boolean = true,
    ) = AuthenticatedPrincipal(
        subjectId = Uuid.random().toString(),
        kind = PrincipalKind.SERVICE,
        scopes = scopes.toSet(),
        orgId = bound?.toString(),
        namedOrg = named,
        onBehalfOf = creator.userId.toString().takeIf { onBehalfOf },
    )

    @Test
    fun aKeyOfMemberScopesActsAsItsCreatorAsAPlainMemberWhateverTheirRole() = runTest {
        val owner = join(acme, OrgRole.OWNER)
        val expected = OrgPrincipal(acme, owner.id, OrgRole.MEMBER)
        assertEquals(expected, resolver.resolve(key(owner, "inference:use"), null))
        assertEquals(expected, resolver.resolve(key(owner, "usage:read"), acme))
    }

    @Test
    fun aKeyOfAReservedScopeStillActsAsAPlainMemberAndOnlyWhileItsCreatorIsAnOwnerOrAnAdmin() = runTest {
        val owner = join(acme, OrgRole.OWNER)
        val admin = join(acme, OrgRole.ADMIN)
        assertEquals(OrgPrincipal(acme, owner.id, OrgRole.MEMBER), resolver.resolve(key(owner, "team:read"), null))
        assertEquals(OrgPrincipal(acme, admin.id, OrgRole.MEMBER), resolver.resolve(key(admin, "cloud:admin"), null))
        // The creator is demoted: the key stops at the next request, without a revocation.
        store.members[admin.id] = admin.copy(role = OrgRole.MEMBER)
        assertNull(resolver.resolve(key(admin, "team:read", "usage:read"), null))
        // The member scopes of the same creator still work, as a plain member.
        assertEquals(OrgPrincipal(acme, admin.id, OrgRole.MEMBER), resolver.resolve(key(admin, "usage:read"), null))
    }

    @Test
    fun aKeyActsInNoOrganizationWhenItsCreatorLeftOrItIsBoundToNoneOrNamesAnother() = runTest {
        val ada = join(acme, OrgRole.MEMBER)
        val inOther = join(other, OrgRole.OWNER)
        assertNull(resolver.resolve(key(ada, "inference:use", bound = null), null), "A row without organization")
        assertNull(resolver.resolve(key(ada, "inference:use", named = other.toString()), null), "Another organization")
        assertNull(resolver.resolve(key(inOther, "inference:use"), null), "A creator of another organization")
        assertNull(resolver.resolve(key(ada, "inference:use", onBehalfOf = false), null), "A CI key: nobody")
        store.members.remove(ada.id)
        assertNull(resolver.resolve(key(ada, "inference:use"), null), "A creator who left")
    }
}
