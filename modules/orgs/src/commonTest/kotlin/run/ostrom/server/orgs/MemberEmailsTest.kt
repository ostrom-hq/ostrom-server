// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.fakes.FakeMembers
import run.ostrom.server.orgs.fakes.FakePeople
import run.ostrom.server.orgs.fakes.OrgsStore
import run.ostrom.server.orgs.infrastructure.MemberEmailsImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The address of a member: their verified primary e-mail, and nobody of another organization. */
class MemberEmailsTest {
    private val modules = listOf(IdentityModule, OrgsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    @Test
    fun aMemberIsWrittenToAtTheirVerifiedPrimaryEmailOnly() = runTest {
        val store = OrgsStore()
        val people = FakePeople()
        val emails = MemberEmailsImpl(FakeMembers(store), people)
        val acme = Uuid.random()
        val ada = people.add("ada", email = "Ada@Example.com", verified = setOf("ada@example.com"))
        val bob = people.add("bob", verified = emptySet())
        val cleo = people.add("cleo", email = null)
        val dan = people.add("dan")
        val adaMember = Member(Uuid.random(), acme, ada.id, OrgRole.OWNER, now)
        val bobMember = Member(Uuid.random(), acme, bob.id, OrgRole.MEMBER, now)
        val cleoMember = Member(Uuid.random(), acme, cleo.id, OrgRole.MEMBER, now)
        val gone = Member(Uuid.random(), acme, Uuid.random(), OrgRole.MEMBER, now)
        val danElsewhere = Member(Uuid.random(), Uuid.random(), dan.id, OrgRole.OWNER, now)
        listOf(adaMember, bobMember, cleoMember, gone, danElsewhere).forEach { store.members[it.id] = it }

        assertEquals("Ada@Example.com", emails.emailOf(acme, adaMember.id), "Verified in any case, given as stored")
        assertNull(emails.emailOf(acme, bobMember.id), "A primary e-mail never verified")
        assertNull(emails.emailOf(acme, cleoMember.id), "A person without an e-mail")
        assertNull(emails.emailOf(acme, gone.id), "A member without a person")
        assertNull(emails.emailOf(acme, danElsewhere.id), "Another organization's member")
        assertNull(emails.emailOf(acme, Uuid.random()), "Nobody")
    }

    @Test
    fun theOrganizationsModuleBindsThemUnderRowLevelSecurity() = serverTest(modules) { server ->
        val ada = server.user("ada")
        val bob = server.user("bob")
        val acme = server.organization("acme", ada)
        val globex = server.organization("globex", bob)
        val emails = server.koin.get<MemberEmails>()

        assertEquals("ada@example.com", emails.emailOf(acme.first, acme.second))
        assertNull(emails.emailOf(acme.first, globex.second))
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
