// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A group of members of one organization and the shared accounts lent to it (the contract's `Group`): the way an
 * admin lends a subscription to a team rather than to people one by one.
 *
 * @property id The group.
 * @property orgId Its organization.
 * @property name Its name, unique in the organization whatever the case.
 * @property members Its members, each maybe paused.
 * @property accounts The shared accounts lent to it.
 * @property createdAt When it was made.
 */
data class SharingGroup(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val members: List<GroupMember>,
    val accounts: Set<Uuid>,
    val createdAt: Instant,
)

/**
 * A member of a group.
 *
 * @property memberId The organization member.
 * @property pausedUntil Until when the group lends them nothing; null or past when they are not paused.
 */
data class GroupMember(val memberId: Uuid, val pausedUntil: Instant? = null)

/**
 * The groups of the organizations, every call confined to the organization it names (row-level security on
 * `sharing_groups`, `sharing_group_members` and `sharing_group_accounts`).
 */
interface GroupRepository {
    /** The organization's groups by name, at most [GroupRules.MAX_GROUPS]. */
    suspend fun list(orgId: Uuid): List<SharingGroup>

    /** One group, or null when the organization has none with this id. */
    suspend fun get(orgId: Uuid, id: Uuid): SharingGroup?

    /** How many groups the organization has. */
    suspend fun count(orgId: Uuid): Int

    /**
     * Saves a new group with its members and accounts; an account id the organization does not share is skipped.
     *
     * @return False when the organization already has a group of this name (any case): nothing is saved.
     */
    suspend fun create(group: SharingGroup): Boolean

    /**
     * Renames a group and replaces its accounts, each when given.
     *
     * @return False when another group of the organization has this name: nothing changes.
     */
    suspend fun update(orgId: Uuid, id: Uuid, name: String?, accounts: Set<Uuid>?): Boolean

    /** Deletes a group, its members and its accounts; false when there was none. */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean

    /** Adds a member to a group; one already there keeps their pause. */
    suspend fun addMember(orgId: Uuid, id: Uuid, memberId: Uuid)

    /** Removes a member from a group; false when they were not in it. */
    suspend fun removeMember(orgId: Uuid, id: Uuid, memberId: Uuid): Boolean

    /** Pauses a member of a group until [until]; false when they are not in it. */
    suspend fun pauseMember(orgId: Uuid, id: Uuid, memberId: Uuid, until: Instant): Boolean
}

/** The limits of the groups. */
object GroupRules {
    /** Groups per organization. */
    const val MAX_GROUPS: Int = 100

    /** Members per group, as many as an account may name ([SharingRules.MAX_ALLOWED_MEMBERS]). */
    const val MAX_MEMBERS: Int = SharingRules.MAX_ALLOWED_MEMBERS

    /** Accounts per group, as many as an organization may share ([SharingRules.MAX_ACCOUNTS]). */
    const val MAX_ACCOUNTS: Int = SharingRules.MAX_ACCOUNTS

    private val name = Regex("[^\\p{Cntrl}]{1,120}")

    /**
     * A group's name, trimmed.
     *
     * @throws InvalidGroupException When it is blank, too long or holds a control character.
     */
    fun name(value: String): String = value.trim().takeIf { name.matches(it) }
        ?: throw InvalidGroupException("name is 1 to 120 characters, without control characters.")
}

/** The organization has no group with this id. */
class GroupNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Group not found.")

/** The group has no member with this login. */
class GroupMemberNotFoundException : OstromException(ErrorKind.NOT_FOUND, "This person is not in the group.")

/** No member of the organization has this login. */
class OrgMemberNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "No member of the organization has this login.")

/** Another group of the organization has this name. */
class GroupNameTakenException : OstromException(ErrorKind.CONFLICT, "Another group already has this name.")

/** The organization has [GroupRules.MAX_GROUPS] groups. */
class TooManyGroupsException :
    OstromException(ErrorKind.CONFLICT, "An organization has at most ${GroupRules.MAX_GROUPS} groups.")

/** A group request the server refuses. */
class InvalidGroupException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
