// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.ORG
import run.ostrom.server.launches.fakes.OTHER_ORG
import run.ostrom.server.launches.infrastructure.IdentityLauncherResolver
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.MembersRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityLauncherResolverTest {
    private val ghost = Uuid.parse("000000ff-0000-0000-0000-000000000000")
    private val outsider = Uuid.parse("000000ee-0000-0000-0000-000000000000")
    private val asked = mutableListOf<Uuid?>()
    private val seen = mutableListOf<AuthenticatedPrincipal>()

    // Ada and the ghost (a member whose user is gone) are members; the outsider is not.
    private val orgs = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
            asked += orgId
            seen += principal
            val member = principal.subjectId != outsider.toString()
            return if (member) OrgPrincipal(ORG, Uuid.random(), OrgRole.MEMBER) else null
        }
    }
    private val adaMember = Uuid.parse("0000a0ad-0000-0000-0000-000000000000")
    private val ghostMember = Uuid.parse("0000a0ff-0000-0000-0000-000000000000")
    private val resolver = IdentityLauncherResolver(orgs, OnlyAda(), AdaAndTheGhost())

    private fun person(subject: String, kind: PrincipalKind = PrincipalKind.USER) =
        AuthenticatedPrincipal(subject, kind, setOf("sessions:write"))

    @Test
    fun aMemberIsTheLauncherOfTheOrganizationTheOrgsModuleResolves() = runTest {
        assertEquals(ADA, resolver.resolve(person(ADA.userId.toString())))
        assertEquals(listOf<Uuid?>(null), asked, "The organization is the credential's, never the request's body")
    }

    @Test
    fun aPersonsRoleIsReadNowInTheLaunchsOrganization() = runTest {
        assertEquals(OrgRole.MEMBER, resolver.member(ORG, ADA.userId)?.role)
        assertEquals(listOf<Uuid?>(ORG), asked)
        assertEquals(ORG.toString(), seen.single().orgId, "As a credential bound to that organization")
        assertEquals(PrincipalKind.USER to ADA.userId.toString(), seen.single().kind to seen.single().subjectId)
        assertNull(resolver.member(ORG, outsider), "No longer a member")
    }

    @Test
    fun anythingButAPersonOfAnOrganizationIsNobody() = runTest {
        assertNull(resolver.resolve(person(ADA.userId.toString(), PrincipalKind.AGENT)))
        assertNull(resolver.resolve(person(ADA.userId.toString(), PrincipalKind.DEVICE)))
        assertNull(resolver.resolve(person("not-a-user")))
        assertEquals(emptyList<Uuid?>(), asked, "Never asked for a credential that is not a person's")
        assertNull(resolver.resolve(person(outsider.toString())), "A member of no organization")
        assertNull(resolver.resolve(person(ghost.toString())), "A member whose user is gone")
    }

    @Test
    fun aCloudSessionsOwnerIsResolvedFromTheirMemberId() = runTest {
        assertEquals(ADA, resolver.owner(ORG, adaMember))
        assertNull(resolver.owner(ORG, Uuid.random()), "No longer a member")
        assertNull(resolver.owner(ORG, ghostMember), "A member whose user is gone")
        assertEquals(emptyList<Uuid?>(), asked, "Read from the members, not from a credential")
    }

    @Test
    fun anotherDeveloperIsReadByLoginInTheOrganizationOnly() = runTest {
        assertEquals(ADA, resolver.colleague(ORG, "ada"))
        assertEquals(ADA, resolver.colleague(ORG, "Ada"), "A login is matched as the users repository matches it")
        assertNull(resolver.colleague(OTHER_ORG, "ada"), "Not a member of that organization")
        assertNull(resolver.colleague(ORG, "nobody"), "No such login")
        assertEquals(emptyList<Uuid?>(), asked, "Read from the members, not from a credential")
    }

    /** Ada's member and the ghost's, in [ORG]; the launches module reads nothing else from the members. */
    private inner class AdaAndTheGhost : MembersRepository {
        private val at = Instant.parse("2026-10-30T10:00:00Z")
        private val members = listOf(
            Member(adaMember, ORG, ADA.userId, ADA.role, at),
            Member(ghostMember, ORG, ghost, OrgRole.MEMBER, at),
        )

        override suspend fun findById(orgId: Uuid, memberId: Uuid): Member? =
            members.firstOrNull { it.orgId == orgId && it.id == memberId }

        override suspend fun findByUser(orgId: Uuid, userId: Uuid): Member? =
            members.firstOrNull { it.orgId == orgId && it.userId == userId }

        override suspend fun list(orgId: Uuid): List<Member> = unused()

        override suspend fun page(orgId: Uuid, after: Uuid?, limit: Int): List<Member> = unused()

        override suspend fun search(orgId: Uuid, prefix: String, after: Uuid?, limit: Int): List<Member> = unused()

        override suspend fun count(orgId: Uuid): Int = unused()

        override suspend fun changeRole(orgId: Uuid, memberId: Uuid, role: OrgRole) = unused()

        override suspend fun remove(orgId: Uuid, memberId: Uuid) = unused()

        override suspend fun transferOwnership(orgId: Uuid, oldOwnerId: Uuid, newOwnerId: Uuid) = unused()

        private fun unused(): Nothing = error("The launches module only reads a member by id or by user.")
    }

    /** A users repository that knows Ada only; the launches module reads nothing else from it. */
    private class OnlyAda : UsersRepository {
        private val ada = User(ADA.userId, "ada", "Ada", null, null, Instant.parse("2026-10-01T00:00:00Z"))

        override suspend fun get(id: Uuid): User? = ada.takeIf { id == it.id }

        override suspend fun findByIdentity(issuer: String, subject: String): User? = unused()

        override suspend fun findByVerifiedEmail(email: String): User? = unused()

        override suspend fun loginTaken(login: String): Boolean = unused()

        override suspend fun any(): Boolean = unused()

        override suspend fun findByLogin(login: String): User? = ada.takeIf { login.lowercase() == it.login }

        override suspend fun getMany(ids: Collection<Uuid>): List<User> = unused()

        override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

        override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

        override suspend fun listEmails(userId: Uuid): List<UserEmail> = unused()

        override suspend fun listIssuers(userId: Uuid): List<String> = unused()

        private fun unused(): Nothing = error("The launches module only reads a user by id or by login.")
    }
}
