// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * The logins of an organization's members, the names the API gives people (`/api/v1/orgs/{org}/members` serves no
 * member id). The orgs module binds it; a module that names people by login asks it, and keeps its own fail-closed
 * default when it is unbound.
 */
interface MemberLogins {
    /**
     * The members of [orgId] with these logins.
     *
     * @param orgId The organization.
     * @param logins The logins, any case.
     *
     * @return The member id of each login asked that names a member of [orgId], keyed as asked; any other is absent.
     */
    suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid>

    /**
     * The logins of these members of [orgId].
     *
     * @param orgId The organization.
     * @param memberIds The members' ids, as [OrgPrincipal.memberId] gives them.
     *
     * @return The login of each id that is a member of [orgId]; a member who left is absent.
     */
    suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String>
}
