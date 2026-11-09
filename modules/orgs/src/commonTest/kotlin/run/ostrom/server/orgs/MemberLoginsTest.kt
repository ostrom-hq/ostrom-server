// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.fakes.FakeMembers
import run.ostrom.server.orgs.fakes.FakePeople
import run.ostrom.server.orgs.fakes.OrgsStore
import run.ostrom.server.orgs.infrastructure.MemberLoginsImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The logins of an organization's members, both ways, and nobody of another organization. */
class MemberLoginsTest {
    private val modules = listOf(IdentityModule, OrgsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    @Test
    fun aLoginNamesAMemberOfTheOrganizationAndAMemberIsNamedByItsLogin() = runTest {
        val store = OrgsStore()
        val people = FakePeople()
        val logins = MemberLoginsImpl(FakeMembers(store), people)
        val acme = Uuid.random()
        val ada = people.add("ada")
        val bob = people.add("bob")
        val gone = Member(Uuid.random(), acme, Uuid.random(), OrgRole.MEMBER, now)
        val adaMember = Member(Uuid.random(), acme, ada.id, OrgRole.OWNER, now)
        val bobElsewhere = Member(Uuid.random(), Uuid.random(), bob.id, OrgRole.OWNER, now)
        listOf(gone, adaMember, bobElsewhere).forEach { store.members[it.id] = it }

        assertEquals(
            mapOf("ADA" to adaMember.id, "ada" to adaMember.id),
            logins.idsOf(acme, listOf("ADA", "ada", "ada", "bob", "nobody")),
            "Keyed as asked; a person of another organization and an unknown login are absent",
        )
        assertEquals(
            mapOf(adaMember.id to "ada"),
            logins.loginsOf(acme, listOf(adaMember.id, gone.id, bobElsewhere.id, Uuid.random())),
            "A member without a person and another organization's member are absent",
        )
        assertEquals(emptyMap(), logins.loginsOf(acme, emptyList()))
        assertEquals(emptyMap(), logins.idsOf(acme, emptyList()))
    }

    @Test
    fun theOrganizationsModuleBindsThemUnderRowLevelSecurity() = serverTest(modules) { server ->
        val ada = server.user("ada")
        val bob = server.user("bob")
        val acme = server.organization("acme", ada)
        val globex = server.organization("globex", bob)
        val logins = server.koin.get<MemberLogins>()

        assertEquals(mapOf("Ada" to acme.second), logins.idsOf(acme.first, listOf("Ada", "bob")))
        assertEquals(mapOf(acme.second to "ada"), logins.loginsOf(acme.first, listOf(acme.second, globex.second)))
    }

    private suspend fun TestServer.user(login: String): User = koin.get<ResolveUserUseCase>()(
        VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
    )

    private suspend fun TestServer.organization(slug: String, owner: User): Pair<Uuid, Uuid> {
        val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
        val member = Member(Uuid.random(), org.id, owner.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        return org.id to member.id
    }
}
