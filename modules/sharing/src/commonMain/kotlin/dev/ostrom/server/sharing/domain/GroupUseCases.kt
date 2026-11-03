// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import dev.ostrom.server.kernel.auth.MemberLogins
import dev.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A group as the API names it: people by login (else by member id, for one who left), accounts by id.
 *
 * @property id The group.
 * @property name Its name.
 * @property members The logins of its members, sorted.
 * @property paused Its members paused now, by login.
 * @property accounts The ids of the shared accounts lent to it, sorted.
 */
data class NamedGroup(
    val id: Uuid,
    val name: String,
    val members: List<String>,
    val paused: List<PausedMember>,
    val accounts: List<String>,
)

/**
 * A member a group lends nothing to until [until].
 *
 * @property login The member's login.
 * @property until The end of the pause.
 */
data class PausedMember(val login: String, val until: Instant)

/**
 * A new group, as sent.
 *
 * @property name Its name.
 * @property members The logins of its members.
 * @property accounts The ids of the shared accounts lent to it.
 */
data class GroupDraft(val name: String, val members: List<String>, val accounts: List<String>)

/**
 * A change to a group: each value given replaces the current one.
 *
 * @property name The new name.
 * @property accounts The ids of the accounts lent to it, all of them.
 */
data class GroupChange(val name: String?, val accounts: List<String>?)

/** The organization's groups: every member reads them, owners and admins write them. */
interface GroupsUseCase {
    /** The organization's groups by name. */
    suspend fun list(caller: Caller): List<NamedGroup>

    /** One group; [GroupNotFoundException] when the organization has none with this id. */
    suspend fun get(caller: Caller, id: Uuid): NamedGroup

    /** Makes a group, for an owner or an admin. */
    suspend fun create(caller: Caller, draft: GroupDraft): NamedGroup

    /** Renames a group or replaces its accounts, for an owner or an admin. */
    suspend fun update(caller: Caller, id: Uuid, change: GroupChange): NamedGroup

    /** Deletes a group, for an owner or an admin: its accounts are no longer lent through it. */
    suspend fun delete(caller: Caller, id: Uuid)
}

/** The members of a group, changed by an owner or an admin. */
interface GroupMembersUseCase {
    /** Adds a member of the organization, named by login; one already in the group stays as they are. */
    suspend fun add(caller: Caller, id: Uuid, login: String)

    /** Removes a member from the group. */
    suspend fun remove(caller: Caller, id: Uuid, login: String)

    /** Pauses a member of the group until [until]; a past instant ends their pause. */
    suspend fun pause(caller: Caller, id: Uuid, login: String, until: Instant)
}

/**
 * [GroupsUseCase] on the groups, the shared accounts (an account lent must be one of the organization's) and the
 * members' logins.
 */
class GroupsUseCaseImpl(
    private val groups: GroupRepository,
    private val accounts: SharedAccountRepository,
    private val logins: MemberLogins,
    private val clock: Clock,
) : GroupsUseCase {
    override suspend fun list(caller: Caller): List<NamedGroup> = named(caller.orgId, groups.list(caller.orgId))

    override suspend fun get(caller: Caller, id: Uuid): NamedGroup =
        named(caller.orgId, listOf(groups.get(caller.orgId, id) ?: throw GroupNotFoundException())).single()

    override suspend fun create(caller: Caller, draft: GroupDraft): NamedGroup {
        caller.requireAdmin()
        val name = GroupRules.name(draft.name)
        val members = memberIds(caller.orgId, draft.members)
        val lent = accountIds(caller.orgId, draft.accounts)
        if (groups.count(caller.orgId) >= GroupRules.MAX_GROUPS) throw TooManyGroupsException()
        val group = SharingGroup(Uuid.random(), caller.orgId, name, members.map(::GroupMember), lent, clock.now())
        if (!groups.create(group)) throw GroupNameTakenException()
        return named(caller.orgId, listOf(group)).single()
    }

    override suspend fun update(caller: Caller, id: Uuid, change: GroupChange): NamedGroup {
        caller.requireAdmin()
        val name = change.name?.let(GroupRules::name)
        val lent = change.accounts?.let { accountIds(caller.orgId, it) }
        groups.get(caller.orgId, id) ?: throw GroupNotFoundException()
        if (!groups.update(caller.orgId, id, name, lent)) throw GroupNameTakenException()
        return get(caller, id)
    }

    override suspend fun delete(caller: Caller, id: Uuid) {
        caller.requireAdmin()
        if (!groups.delete(caller.orgId, id)) throw GroupNotFoundException()
    }

    private suspend fun memberIds(orgId: Uuid, values: List<String>): Set<Uuid> {
        val wanted = values.distinct()
        if (wanted.size > GroupRules.MAX_MEMBERS) {
            throw InvalidGroupException("A group has at most ${GroupRules.MAX_MEMBERS} members.")
        }
        val ids = logins.idsOf(orgId, wanted)
        return wanted.map {
            ids[it] ?: throw InvalidGroupException("members names members of the organization by login.")
        }.toSet()
    }

    private suspend fun accountIds(orgId: Uuid, values: List<String>): Set<Uuid> {
        val refusal = "accounts names shared accounts of the organization by id."
        val wanted = values.map { Uuid.parseOrNull(it) ?: throw InvalidGroupException(refusal) }.toSet()
        val shared = accounts.list(orgId).map { it.id }.toSet()
        if (!shared.containsAll(wanted)) throw InvalidGroupException(refusal)
        return wanted
    }

    private suspend fun named(orgId: Uuid, found: List<SharingGroup>): List<NamedGroup> {
        val known = logins.loginsOf(orgId, found.flatMap { group -> group.members.map { it.memberId } }.toSet())
        val now = clock.now()
        val nameOf = { id: Uuid -> known[id] ?: id.toString() }
        return found.map { group ->
            NamedGroup(
                id = group.id,
                name = group.name,
                members = group.members.map { nameOf(it.memberId) }.sorted(),
                paused = group.members.mapNotNull { member ->
                    member.pausedUntil?.takeIf { it > now }?.let { PausedMember(nameOf(member.memberId), it) }
                }.sortedBy { it.login },
                accounts = group.accounts.map { it.toString() }.sorted(),
            )
        }
    }
}

/** [GroupMembersUseCase] on the groups and the members' logins. */
class GroupMembersUseCaseImpl(private val groups: GroupRepository, private val logins: MemberLogins) :
    GroupMembersUseCase {
    override suspend fun add(caller: Caller, id: Uuid, login: String) {
        caller.requireAdmin()
        val group = groups.get(caller.orgId, id) ?: throw GroupNotFoundException()
        val memberId = logins.idsOf(caller.orgId, listOf(login))[login] ?: throw OrgMemberNotFoundException()
        group.requireRoomFor(memberId)
        groups.addMember(caller.orgId, id, memberId)
    }

    override suspend fun remove(caller: Caller, id: Uuid, login: String) {
        val memberId = inGroup(caller, id, login)
        if (!groups.removeMember(caller.orgId, id, memberId)) throw GroupMemberNotFoundException()
    }

    override suspend fun pause(caller: Caller, id: Uuid, login: String, until: Instant) {
        val memberId = inGroup(caller, id, login)
        if (!groups.pauseMember(caller.orgId, id, memberId, until)) throw GroupMemberNotFoundException()
    }

    /**
     * The member [login] names in an existing group: a member's login, or the member id the group shows for one who
     * left the organization.
     */
    private suspend fun inGroup(caller: Caller, id: Uuid, login: String): Uuid {
        caller.requireAdmin()
        groups.get(caller.orgId, id) ?: throw GroupNotFoundException()
        return logins.idsOf(caller.orgId, listOf(login))[login] ?: Uuid.parseOrNull(login)
            ?: throw GroupMemberNotFoundException()
    }
}

/** Refuses a new member once the group has [GroupRules.MAX_MEMBERS]; one already in it always fits. */
private fun SharingGroup.requireRoomFor(memberId: Uuid) {
    if (members.none { it.memberId == memberId } && members.size >= GroupRules.MAX_MEMBERS) {
        throw InvalidGroupException("A group has at most ${GroupRules.MAX_MEMBERS} members.")
    }
}
