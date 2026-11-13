// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.CreateOrgUseCaseImpl
import run.ostrom.server.orgs.domain.OrgCreationGuards
import run.ostrom.server.orgs.domain.FindOrgUseCaseImpl
import run.ostrom.server.orgs.domain.FreeTeamTakenException
import run.ostrom.server.orgs.domain.GetOrgUseCaseImpl
import run.ostrom.server.orgs.domain.InvalidOrgRequestException
import run.ostrom.server.orgs.domain.ListMyOrgsUseCaseImpl
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.MemberNotFoundException
import run.ostrom.server.orgs.domain.Membership
import run.ostrom.server.orgs.domain.NotAllowedException
import run.ostrom.server.orgs.domain.OrgCreationClosedException
import run.ostrom.server.orgs.domain.OrgNotFoundException
import run.ostrom.server.orgs.domain.OrgRateLimitedException
import run.ostrom.server.orgs.domain.Person
import run.ostrom.server.orgs.domain.SlugTakenException
import run.ostrom.server.orgs.domain.TransferOwnershipUseCaseImpl
import run.ostrom.server.orgs.fakes.OrgsWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class OrgUseCasesTest {
    private val world = OrgsWorld()
    private val create =
        CreateOrgUseCaseImpl(
            world.orgs,
            world.people,
            OrgCreationGuards(world.creation, world.limiter),
            world.seats,
            world.audit,
            world.clock,
        )

    /** Adds [person] to [membership]'s organization with [role]. */
    private fun join(membership: Membership, person: Person, role: OrgRole): Member {
        val member = Member(Uuid.random(), membership.org.id, person.id, role, world.clock.now)
        world.store.members[member.id] = member
        return member
    }

    @Test
    fun theCreatorOwnsTheNewOrganizationAndItIsAudited() = runTest {
        val ada = world.people.add("ada", "ada@acme.com")

        val created = create(ada.id, " Acme Inc ", null)

        assertEquals("acme-inc", created.org.slug)
        assertEquals("Acme Inc", created.org.name)
        assertEquals("domain:acme.com", created.org.freeClaim)
        assertEquals(OrgRole.OWNER, created.member.role)
        assertEquals(created.member, world.members.findByUser(created.org.id, ada.id))
        assertEquals("org.created" to mapOf("slug" to "acme-inc"), world.audit.events.single())
    }

    @Test
    fun anInviteOnlyInstanceLetsOnlyItsOwnersAdminsAndSetupPersonCreateOrganizations() = runTest {
        val first = world.people.add("first", null)
        val admin = world.people.add("admin", null)
        val dev = world.people.add("dev", null)
        val stranger = world.people.add("stranger", null)
        world.creation.open = false
        world.creation.allowed += first.id

        val acme = create(first.id, "Acme", null)
        join(acme, admin, OrgRole.ADMIN)
        join(acme, dev, OrgRole.MEMBER)

        assertEquals(OrgRole.OWNER, create(admin.id, "Acme labs", null).member.role, "An admin creates one")
        val refused = assertFailsWith<OrgCreationClosedException> { create(dev.id, "Side", null) }
        assertEquals("org_creation_closed", refused.type)
        assertFailsWith<OrgCreationClosedException>("Nobody's member") { create(stranger.id, "Mine", null) }
        assertEquals("QA", create(stranger.id, "QA", null, operator = true).org.name, "The operator seeds one")
    }

    @Test
    fun aChosenShortNameIsKeptAndATakenOneRefused() = runTest {
        val ada = world.people.add("ada", null)
        assertEquals("labs", create(ada.id, "Acme", "labs").org.slug)
        val bob = world.people.add("bob", null)
        assertFailsWith<SlugTakenException> { create(bob.id, "Other", "labs") }
        assertEquals("acme", create(bob.id, "Acme", null).org.slug)
        val carl = world.people.add("carl", null)
        assertEquals("acme-2", create(carl.id, "Acme", null).org.slug)
        assertFailsWith<InvalidOrgRequestException> { create(carl.id, "", null) }
        assertFailsWith<InvalidOrgRequestException> { create(carl.id, "X", "No") }
    }

    @Test
    fun oneDomainOrOnePersonHasOneFreeTeamUnlessItPays() = runTest {
        val ada = world.people.add("ada", "ada@acme.com")
        val bob = world.people.add("bob", "bob@acme.com")
        val first = create(ada.id, "Acme", null)

        val refused = assertFailsWith<FreeTeamTakenException> { create(bob.id, "Acme two", null) }
        assertEquals(
            "A free team already exists for the domain acme.com: join it, or upgrade it to add another team.",
            refused.message,
        )
        val solo = world.people.add("solo", "solo@gmail.com")
        create(solo.id, "Mine", null)
        assertEquals(
            "A free team already exists for your account: join it, or upgrade it to add another team.",
            assertFailsWith<FreeTeamTakenException> { create(solo.id, "Second", null) }.message,
        )

        world.seats.paying += first.org.id
        assertEquals("domain:acme.com", create(bob.id, "Acme two", null).org.freeClaim)
    }

    @Test
    fun creationsAreRateLimited() = runTest {
        world.limiter.allow = false
        assertFailsWith<OrgRateLimitedException> { create(Uuid.random(), "Acme", null) }
    }

    @Test
    fun aMemberFindsListsAndReadsTheirOrganizations() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val find = FindOrgUseCaseImpl(world.orgs)

        assertEquals(acme.org.id, find(acme.org.id.toString()))
        assertEquals(acme.org.id, find("ACME"))
        assertFailsWith<OrgNotFoundException> { find(Uuid.random().toString()) }
        assertFailsWith<OrgNotFoundException> { find("nope") }
        assertEquals(listOf(acme), ListMyOrgsUseCaseImpl(world.orgs)(ada.id))
        val get = GetOrgUseCaseImpl(world.orgs, world.members)
        assertEquals(acme, get(acme.org.id, ada.id))
        assertFailsWith<OrgNotFoundException> { get(acme.org.id, Uuid.random()) }
        assertFailsWith<OrgNotFoundException> { get(Uuid.random(), ada.id) }
    }

    @Test
    fun onlyTheOwnerTransfersToAnotherMemberAndStaysAdmin() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val bob = world.people.add("bob", null)
        val bobMember = join(acme, bob, OrgRole.MEMBER)
        val stranger = world.people.add("carl", null)
        val transfer =
            TransferOwnershipUseCaseImpl(world.orgs, world.members, world.people, world.audit)

        assertFailsWith<NotAllowedException> { transfer(acme.org.id, bob.id, "ada") }
        assertFailsWith<InvalidOrgRequestException> { transfer(acme.org.id, ada.id, "ada") }
        assertFailsWith<MemberNotFoundException> { transfer(acme.org.id, ada.id, "nobody") }
        assertFailsWith<MemberNotFoundException> { transfer(acme.org.id, ada.id, stranger.login) }
        transfer(acme.org.id, ada.id, "BOB")

        assertEquals(OrgRole.OWNER, world.members.findByUser(acme.org.id, bob.id)?.role)
        assertEquals(OrgRole.ADMIN, world.members.findByUser(acme.org.id, ada.id)?.role)
        assertEquals("org.ownership.transferred" to mapOf("to" to "bob"), world.audit.events.last())
        assertEquals(bobMember.id, world.members.findByUser(acme.org.id, bob.id)?.id)
    }
}
