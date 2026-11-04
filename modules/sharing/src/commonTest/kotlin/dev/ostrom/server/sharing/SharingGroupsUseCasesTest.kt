// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.auth.MemberLogins
import dev.ostrom.server.sharing.domain.GroupDraft
import dev.ostrom.server.sharing.domain.GroupMember
import dev.ostrom.server.sharing.domain.GroupMembersUseCaseImpl
import dev.ostrom.server.sharing.domain.GroupRepository
import dev.ostrom.server.sharing.domain.GroupRules
import dev.ostrom.server.sharing.domain.GroupsUseCaseImpl
import dev.ostrom.server.sharing.domain.InvalidGroupException
import dev.ostrom.server.sharing.domain.SharingGroup
import dev.ostrom.server.sharing.domain.TooManyGroupsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The limits of the groups, which the PostgreSQL test cannot reach at a reasonable cost. */
class SharingGroupsUseCasesTest {
    /** Every login `pN` names a member of org A. */
    private val logins = object : MemberLogins {
        val ids = mutableMapOf<String, Uuid>()

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            logins.filter { it.startsWith("p") }.associateWith { ids.getOrPut(it) { Uuid.random() } }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            ids.entries.filter { it.value in memberIds }.associate { it.value to it.key }
    }

    /** Groups in memory, with as many groups as the test says. */
    private class Groups(var counted: Int = 0) : GroupRepository {
        val saved = mutableMapOf<Uuid, SharingGroup>()
        var added = 0

        override suspend fun list(orgId: Uuid) = saved.values.toList()

        override suspend fun get(orgId: Uuid, id: Uuid) = saved[id]

        override suspend fun count(orgId: Uuid) = counted

        override suspend fun create(group: SharingGroup): Boolean {
            saved[group.id] = group
            return true
        }

        override suspend fun update(orgId: Uuid, id: Uuid, name: String?, accounts: Set<Uuid>?) = true

        override suspend fun delete(orgId: Uuid, id: Uuid) = saved.remove(id) != null

        override suspend fun addMember(orgId: Uuid, id: Uuid, memberId: Uuid) {
            added++
            val group = saved.getValue(id)
            if (group.members.none { it.memberId == memberId }) {
                saved[id] = group.copy(members = group.members + GroupMember(memberId))
            }
        }

        override suspend fun removeMember(orgId: Uuid, id: Uuid, memberId: Uuid) = true

        override suspend fun pauseMember(orgId: Uuid, id: Uuid, memberId: Uuid, until: Instant) = true
    }

    private val clock = TestClock()

    private fun full(groups: Groups): SharingGroup {
        val group = SharingGroup(
            Uuid.random(),
            Sharing.orgA,
            "Everyone",
            List(GroupRules.MAX_MEMBERS) { GroupMember(Uuid.random()) },
            emptySet(),
            clock.now(),
        )
        groups.saved[group.id] = group
        return group
    }

    @Test
    fun aGroupHasAtMostItsMembersAndAnOrganizationAtMostItsGroups() = runTest {
        val groups = Groups()
        val useCase = GroupsUseCaseImpl(groups, InMemoryAccounts(), logins, clock)
        val crowd = List(GroupRules.MAX_MEMBERS + 1) { "p$it" }
        assertFailsWith<InvalidGroupException> {
            useCase.create(Sharing.adminA, GroupDraft("Crowd", crowd, emptyList()))
        }

        val most = useCase.create(Sharing.adminA, GroupDraft("Most", crowd.drop(1), emptyList()))
        assertEquals(GroupRules.MAX_MEMBERS, most.members.size, "The most members a group may have")

        groups.counted = GroupRules.MAX_GROUPS
        assertFailsWith<TooManyGroupsException> {
            useCase.create(Sharing.adminA, GroupDraft("One more", emptyList(), emptyList()))
        }
    }

    @Test
    fun aFullGroupTakesNoNewMemberButKeepsItsOwn() = runTest {
        val groups = Groups()
        val group = full(groups)
        val members = GroupMembersUseCaseImpl(groups, logins)

        assertFailsWith<InvalidGroupException> { members.add(Sharing.adminA, group.id, "p-new") }
        assertEquals(0, groups.added, "Nothing is written for a new member of a full group")

        logins.ids["p-old"] = group.members.first().memberId
        members.add(Sharing.adminA, group.id, "p-old")
        assertEquals(1, groups.added, "A member already in a full group is added again, as is")
        assertEquals(GroupRules.MAX_MEMBERS, groups.saved.getValue(group.id).members.size)
    }
}
