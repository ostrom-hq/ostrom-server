// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.domain

import run.ostrom.server.kernel.auth.OrgRole
import kotlin.uuid.Uuid

/**
 * The member of an organization with a login.
 *
 * @param members The members.
 * @param people The people.
 * @param orgId The organization.
 * @param login The login.
 * @return The member and the person.
 * @throws MemberNotFoundException When no member has that login.
 */
internal suspend fun findMember(
    members: MembersRepository,
    people: People,
    orgId: Uuid,
    login: String,
): MemberProfile {
    val person = people.findByLogin(login) ?: throw MemberNotFoundException()
    val member = members.findByUser(orgId, person.id) ?: throw MemberNotFoundException()
    return profileOf(member, person)
}

/**
 * A member with what the console shows of the person.
 *
 * @param member The membership.
 * @param person The person.
 * @return The profile.
 */
internal fun profileOf(member: Member, person: Person) =
    MemberProfile(member, person.login, person.displayName, person.avatarUrl, person.email)

/**
 * The profile as [viewer] may see it: an owner or an admin sees every member's e-mail address, any other member only
 * their own, so that a member's token (the CLI's, the app's, a personal one with `orgs:read`) never lists the team's
 * addresses. The login and the name stay visible to every member.
 *
 * @receiver The member's full profile.
 * @param viewer The caller's membership.
 * @return The profile, without the address when [viewer] may not see it.
 */
internal fun MemberProfile.seenBy(viewer: Member): MemberProfile =
    if (viewer.role in MANAGER_ROLES || viewer.userId == member.userId) this else copy(email = null)

/**
 * Whether [caller] may change or remove [target]: owners and admins manage the other roles; only
 * the owner manages admins; nobody manages the owner (the ownership moves by transfer).
 *
 * @param caller The caller's role.
 * @param target The target's role.
 * @return True when allowed.
 */
fun canManage(caller: OrgRole, target: OrgRole): Boolean =
    when (target) {
        OrgRole.OWNER -> false
        OrgRole.ADMIN -> caller == OrgRole.OWNER
        else -> caller in MANAGER_ROLES
    }

/**
 * Checks that [caller] may give [target] the role [role], without changing anything.
 *
 * @param caller The caller's membership.
 * @param target The member.
 * @param role Their new role.
 * @throws InvalidOrgRequestException For the owner role: the ownership moves by a transfer.
 * @throws NotAllowedException For the caller's own role, or a role or a member beyond what the caller manages.
 */
internal fun requireRoleChange(caller: Membership, target: MemberProfile, role: OrgRole) {
    ensure(role != OrgRole.OWNER) {
        InvalidOrgRequestException("Transfer the ownership to make someone owner.")
    }
    ensure(target.member.id != caller.member.id) {
        NotAllowedException("You cannot change your own role.")
    }
    ensure(canManage(caller.member.role, target.member.role) && canManage(caller.member.role, role)) {
        NotAllowedException("Your role does not allow this change.")
    }
}

/**
 * Checks that [caller] may remove [target], without changing anything.
 *
 * @param caller The caller's membership.
 * @param target The member.
 * @throws NotAllowedException For the caller themselves (they leave instead), or a member beyond what they manage.
 */
internal fun requireRemoval(caller: Membership, target: MemberProfile) {
    ensure(target.member.id != caller.member.id) {
        NotAllowedException("Leave the organization instead.")
    }
    ensure(canManage(caller.member.role, target.member.role)) {
        NotAllowedException("Your role does not allow this.")
    }
}

/** The members of an organization. */
interface ListMembersUseCase {
    /**
     * Lists them.
     *
     * @param orgId The organization.
     * @param userId The caller, a member.
     * @param after The member the page starts after, the previous page's `next`.
     * @param limit The most members, 1 to [MAX_MEMBER_PAGE].
     * @param query Only the members whose login or name starts with it, whatever the case; 1 to [MAX_MEMBER_QUERY]
     *   characters.
     * @return The members, oldest first.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, after: Uuid?, limit: Int, query: String? = null): MemberPage
}

/**
 * [ListMembersUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 */
class ListMembersUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
) : ListMembersUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, after: Uuid?, limit: Int, query: String?): MemberPage {
        val viewer = requireMembership(orgs, members, orgId, userId).member
        if (limit !in 1..MAX_MEMBER_PAGE) throw InvalidOrgRequestException("limit is 1 to $MAX_MEMBER_PAGE.")
        if (query != null && query.length !in 1..MAX_MEMBER_QUERY) {
            throw InvalidOrgRequestException("q is 1 to $MAX_MEMBER_QUERY characters.")
        }
        // One more than the page says whether another follows. The search runs under its indexes in the database
        // (OrgsMigrations version 7; 10 teams of 5 000 in OrgsReadsBenchTest): only the page is read either way.
        val read = if (query == null) {
            members.page(orgId, after, limit + 1)
        } else {
            members.search(orgId, query, after, limit + 1)
        }
        // A cursor no page of this organization gave (forged, or another organization's member): refused, not an
        // empty page. Checked only when the page is empty, the one case an unknown cursor reaches.
        if (after != null && read.isEmpty() && members.findById(orgId, after) == null) {
            throw InvalidOrgRequestException("after is not a member of this organization.")
        }
        val list = read.take(limit)
        val byId = people.getMany(list.map { it.userId }).associateBy { it.id }
        val items = list.mapNotNull { member -> byId[member.userId]?.let { profileOf(member, it).seenBy(viewer) } }
        return MemberPage(items, if (read.size > limit) list.last().id else null)
    }
}

/** One member of an organization. */
interface GetMemberUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @param userId The caller, a member.
     * @param login The member's login.
     * @return The member.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, login: String): MemberProfile
}

/**
 * [GetMemberUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 */
class GetMemberUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
) : GetMemberUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, login: String): MemberProfile {
        val viewer = requireMembership(orgs, members, orgId, userId).member
        return findMember(members, people, orgId, login).seenBy(viewer)
    }
}

/** Changes a member's role. */
interface ChangeRoleUseCase {
    /**
     * Changes it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @param login The member's login.
     * @param role The new role (never owner: the ownership moves by transfer).
     * @return The member.
     * @throws NotAllowedException When the caller may not manage that member or give that role.
     */
    suspend operator fun invoke(
        orgId: Uuid,
        userId: Uuid,
        login: String,
        role: OrgRole,
    ): MemberProfile
}

/**
 * [ChangeRoleUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 * @param audit The audit log.
 */
class ChangeRoleUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
    private val audit: OrgAudit,
) : ChangeRoleUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        userId: Uuid,
        login: String,
        role: OrgRole,
    ): MemberProfile {
        val caller = requireMembership(orgs, members, orgId, userId)
        val target = findMember(members, people, orgId, login)
        requireRoleChange(caller, target, role)
        if (target.member.role == role) return target
        members.changeRole(orgId, target.member.id, role)
        val details =
            mapOf(
                "login" to target.login,
                "from" to target.member.role.name.lowercase(),
                "to" to role.name.lowercase(),
            )
        audit.record(
            OrgEvent(
                orgId,
                userId,
                "org.member.role_changed",
                "member",
                target.member.id.toString(),
                details,
            )
        )
        return target.copy(member = target.member.copy(role = role))
    }
}

/** Removes a member. */
interface RemoveMemberUseCase {
    /**
     * Removes it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @param login The member's login.
     * @throws NotAllowedException When the caller may not manage that member, or names themselves
     *   (they leave).
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, login: String)
}

/**
 * [RemoveMemberUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 * @param audit The audit log.
 */
class RemoveMemberUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
    private val audit: OrgAudit,
) : RemoveMemberUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, login: String) {
        val caller = requireMembership(orgs, members, orgId, userId)
        val target = findMember(members, people, orgId, login)
        requireRemoval(caller, target)
        members.remove(orgId, target.member.id)
        val details = mapOf("login" to target.login)
        audit.record(
            OrgEvent(
                orgId,
                userId,
                "org.member.removed",
                "member",
                target.member.id.toString(),
                details,
            )
        )
    }
}

/** Leaves an organization. */
interface LeaveOrgUseCase {
    /**
     * Leaves it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @throws NotAllowedException When the caller is the owner (they transfer the ownership or
     *   delete it first).
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid)
}

/**
 * [LeaveOrgUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param audit The audit log.
 */
class LeaveOrgUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val audit: OrgAudit,
) : LeaveOrgUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid) {
        val caller = requireMembership(orgs, members, orgId, userId)
        ensure(caller.member.role != OrgRole.OWNER) {
            NotAllowedException("Transfer the ownership or delete the organization before leaving.")
        }
        members.remove(orgId, caller.member.id)
        audit.record(
            OrgEvent(orgId, userId, "org.member.left", "member", caller.member.id.toString())
        )
    }
}

/** How many people an organization has: its members, for per-seat billing. */
interface CountMembersUseCase {
    /**
     * Counts them.
     *
     * @param orgId The organization.
     * @return The number of members.
     */
    suspend operator fun invoke(orgId: Uuid): Int
}

/**
 * [CountMembersUseCase].
 *
 * @param members The members.
 */
class CountMembersUseCaseImpl(private val members: MembersRepository) : CountMembersUseCase {
    override suspend fun invoke(orgId: Uuid): Int = members.count(orgId)
}
