// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.orgs.domain.ChangeRoleUseCaseImpl
import dev.ostrom.server.orgs.domain.CountMembersUseCaseImpl
import dev.ostrom.server.orgs.domain.GetMemberUseCaseImpl
import dev.ostrom.server.orgs.domain.InvalidOrgRequestException
import dev.ostrom.server.orgs.domain.LeaveOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.ListMembersUseCaseImpl
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.MemberNotFoundException
import dev.ostrom.server.orgs.domain.NotAllowedException
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgNotFoundException
import dev.ostrom.server.orgs.domain.Person
import dev.ostrom.server.orgs.domain.RemoveMemberUseCaseImpl
import dev.ostrom.server.orgs.fakes.OrgsWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class MemberUseCasesTest {
    private val world = OrgsWorld()
    private val org =
        Org(Uuid.random(), "acme", "Acme", null, null, world.clock.now).also {
            world.store.orgs[it.id] = it
        }
    private val ada = world.people.add("ada")
    private val bob = world.people.add("bob")
    private val carl = world.people.add("carl")
    private val dana = world.people.add("dana")

    init {
        join(ada, OrgRole.OWNER)
        join(bob, OrgRole.ADMIN)
        join(carl, OrgRole.MEMBER)
        join(dana, OrgRole.ADMIN)
    }

    private fun join(person: Person, role: OrgRole): Member {
        world.clock.now += 1.minutes
        val member = Member(Uuid.random(), org.id, person.id, role, world.clock.now)
        world.store.members[member.id] = member
        return member
    }

    private suspend fun roleOf(person: Person) = world.members.findByUser(org.id, person.id)?.role

    @Test
    fun membersSeeEveryoneOldestFirstWithTheirProfile() = runTest {
        // A user deleted from the identity module is no longer shown.
        val ghost =
            Member(
                Uuid.random(),
                org.id,
                Uuid.random(),
                OrgRole.MEMBER,
                world.clock.now + 5.minutes,
            )
        world.store.members[ghost.id] = ghost
        val list = ListMembersUseCaseImpl(world.orgs, world.members, world.people)

        val members = list(org.id, carl.id)

        assertEquals(listOf("ada", "bob", "carl", "dana"), members.map { it.login })
        assertEquals("ada@example.com", list(org.id, bob.id).first().email)
        assertFailsWith<OrgNotFoundException> { list(org.id, Uuid.random()) }
        val get = GetMemberUseCaseImpl(world.orgs, world.members, world.people)
        assertEquals(OrgRole.ADMIN, get(org.id, carl.id, "bob").member.role)
        assertFailsWith<MemberNotFoundException> { get(org.id, carl.id, "nobody") }
        assertEquals(5, CountMembersUseCaseImpl(world.members)(org.id))
    }

    @Test
    fun onlyAnOwnerOrAnAdminSeesTheOtherMembersAddresses() = runTest {
        val list = ListMembersUseCaseImpl(world.orgs, world.members, world.people)
        val get = GetMemberUseCaseImpl(world.orgs, world.members, world.people)

        // A member (or a token of theirs: the CLI's, the app's) sees their own address, never the others'.
        val seenByMember = list(org.id, carl.id).associate { it.login to it.email }

        assertEquals(mapOf("ada" to null, "bob" to null, "carl" to "carl@example.com", "dana" to null), seenByMember)
        assertNull(get(org.id, carl.id, "ada").email)
        assertEquals("carl@example.com", get(org.id, carl.id, "carl").email)
        assertEquals(
            listOf("ada@example.com", "bob@example.com", "carl@example.com", "dana@example.com"),
            list(org.id, ada.id).map { it.email },
        )
        assertEquals("carl@example.com", get(org.id, bob.id, "carl").email)
    }

    @Test
    fun rolesChangeWithinWhatTheCallerManages() = runTest {
        val change = ChangeRoleUseCaseImpl(world.orgs, world.members, world.people, world.audit)

        assertEquals(OrgRole.APPROVER, change(org.id, bob.id, "carl", OrgRole.APPROVER).member.role)
        assertEquals(OrgRole.APPROVER, roleOf(carl))
        assertEquals(
            "org.member.role_changed" to
                mapOf("login" to "carl", "from" to "member", "to" to "approver"),
            world.audit.events.single(),
        )
        assertEquals(OrgRole.APPROVER, change(org.id, bob.id, "carl", OrgRole.APPROVER).member.role)
        assertEquals(1, world.audit.events.size, "An unchanged role records nothing")

        assertFailsWith<InvalidOrgRequestException> { change(org.id, ada.id, "bob", OrgRole.OWNER) }
        assertFailsWith<NotAllowedException> { change(org.id, bob.id, "bob", OrgRole.MEMBER) }
        assertFailsWith<NotAllowedException> { change(org.id, bob.id, "dana", OrgRole.MEMBER) }
        assertFailsWith<NotAllowedException> { change(org.id, bob.id, "carl", OrgRole.ADMIN) }
        assertFailsWith<NotAllowedException> { change(org.id, carl.id, "dana", OrgRole.MEMBER) }
        assertFailsWith<NotAllowedException> { change(org.id, bob.id, "ada", OrgRole.MEMBER) }
        assertEquals(OrgRole.BILLING, change(org.id, ada.id, "dana", OrgRole.BILLING).member.role)
    }

    @Test
    fun membersAreRemovedWithinWhatTheCallerManages() = runTest {
        val remove = RemoveMemberUseCaseImpl(world.orgs, world.members, world.people, world.audit)

        assertFailsWith<NotAllowedException> { remove(org.id, bob.id, "bob") }
        assertFailsWith<NotAllowedException> { remove(org.id, bob.id, "dana") }
        assertFailsWith<NotAllowedException> { remove(org.id, bob.id, "ada") }
        remove(org.id, bob.id, "carl")
        assertNull(roleOf(carl))
        assertEquals("org.member.removed" to mapOf("login" to "carl"), world.audit.events.single())
        remove(org.id, ada.id, "dana")
        assertNull(roleOf(dana))
    }

    @Test
    fun anyoneButTheOwnerLeaves() = runTest {
        val leave = LeaveOrgUseCaseImpl(world.orgs, world.members, world.audit)

        assertEquals(
            "Transfer the ownership or delete the organization before leaving.",
            assertFailsWith<NotAllowedException> { leave(org.id, ada.id) }.message,
        )
        leave(org.id, carl.id)
        assertNull(roleOf(carl))
        assertEquals("org.member.left", world.audit.events.single().first)
        assertFailsWith<OrgNotFoundException> { leave(org.id, carl.id) }
    }
}
