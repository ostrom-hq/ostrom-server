// SPDX-License-Identifier: AGPL-3.0-only
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
}
