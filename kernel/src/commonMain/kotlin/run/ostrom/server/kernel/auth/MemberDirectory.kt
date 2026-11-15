// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * Who is an active member of an organization. The orgs module binds it; other modules ask it, such as automations
 * before asking a wait of someone else than its asker. Unbound, each module keeps its own fail-closed default.
 */
interface MemberDirectory {
    /**
     * Whether [memberId] is an active member of [orgId].
     *
     * @param orgId The organization.
     * @param memberId The member's id, as [OrgPrincipal.memberId] gives it.
     *
     * @return True when that organization has a member with this id.
     */
    suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean

    /**
     * Every active member of [orgId], such as the MCP relay's retain sweep needs for a per-member server assigned to
     * the whole organization.
     *
     * @param orgId The organization.
     * @param limit The most ids returned.
     *
     * @return The members' ids, as [OrgPrincipal.memberId] gives them; null when there are more than [limit], or when
     * this directory cannot list them.
     */
    suspend fun memberIds(orgId: Uuid, limit: Int): List<String>? = null

    /**
     * The role of every member of [orgId], such as the picker of a session transfer needs.
     *
     * @param orgId The organization.
     * @param limit The most members returned.
     *
     * @return The role of each member, by the id [OrgPrincipal.memberId] gives; null when there are more than [limit],
     *   or when this directory cannot list them.
     */
    suspend fun roles(orgId: Uuid, limit: Int): Map<String, OrgRole>? = null

    /**
     * The person behind a member of [orgId], for what an owner or an admin reads or changes of them (their devices).
     *
     * @param orgId The organization.
     * @param memberId The member's id, as [OrgPrincipal.memberId] gives it.
     *
     * @return The user id; null for a member of another organization, an unknown member, or a directory that cannot
     *   tell.
     */
    suspend fun userIdOf(orgId: Uuid, memberId: String): Uuid? = null

    /**
     * Whether [userId] is a member of [orgId].
     *
     * @param orgId The organization.
     * @param userId The person.
     *
     * @return True when that organization has a member for this user; false when unknown or when this directory cannot
     *   tell.
     */
    suspend fun hasUser(orgId: Uuid, userId: Uuid): Boolean = false
}
