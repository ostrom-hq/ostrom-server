// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.infrastructure.IdentityLauncherResolver
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
    private val resolver = IdentityLauncherResolver(orgs, OnlyAda())

    private fun person(subject: String, kind: PrincipalKind = PrincipalKind.USER) =
        AuthenticatedPrincipal(subject, kind, setOf("sessions:write"))

    @Test
    fun aMemberIsTheLauncherOfTheOrganizationTheOrgsModuleResolves() = runTest {
        assertEquals(ADA, resolver.resolve(person(ADA.userId.toString())))
        assertEquals(listOf<Uuid?>(null), asked, "The organization is the credential's, never the request's body")
    }

    @Test
    fun aPersonsRoleIsReadNowInTheLaunchsOrganization() = runTest {
        assertEquals(OrgRole.MEMBER, resolver.role(ORG, ADA.userId))
        assertEquals(listOf<Uuid?>(ORG), asked)
        assertEquals(ORG.toString(), seen.single().orgId, "As a credential bound to that organization")
        assertEquals(PrincipalKind.USER to ADA.userId.toString(), seen.single().kind to seen.single().subjectId)
        assertNull(resolver.role(ORG, outsider), "No longer a member")
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

    /** A users repository that knows Ada only; the launches module reads nothing else from it. */
    private class OnlyAda : UsersRepository {
        private val ada = User(ADA.userId, "ada", "Ada", null, null, Instant.parse("2026-10-01T00:00:00Z"))

        override suspend fun get(id: Uuid): User? = ada.takeIf { id == it.id }

        override suspend fun findByIdentity(issuer: String, subject: String): User? = unused()

        override suspend fun findByVerifiedEmail(email: String): User? = unused()

        override suspend fun loginTaken(login: String): Boolean = unused()

        override suspend fun findByLogin(login: String): User? = unused()

        override suspend fun getMany(ids: Collection<Uuid>): List<User> = unused()

        override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

        override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

        override suspend fun listEmails(userId: Uuid): List<UserEmail> = unused()

        override suspend fun listIssuers(userId: Uuid): List<String> = unused()

        private fun unused(): Nothing = error("The launches module only reads a user by id.")
    }
}
