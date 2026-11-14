// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * The member an agent token acts for, read by the module that owns launches.
 *
 * @property member The membership, as it is now, of the person who launched it, with their role.
 * @property launchId The launch the token belongs to.
 * @property cloudSessionId The cloud session the launch was opened for, or null for `ostrom <agent>` on a machine.
 */
data class AgentMember(val member: OrgPrincipal, val launchId: String, val cloudSessionId: String? = null)

/**
 * Reads an agent token ([PrincipalKind.AGENT]) for a module that has no other way to: the person who launched it, in
 * the launch's organization, while the launch lives (TeamCode's execution credential, which acts for the member the
 * execution runs for). The launches module binds it with the one reading of an agent token the gates and the MCP relay
 * use; until one does, a module looks it up with `getOrNull` and no agent token is anyone there (fail closed).
 */
interface AgentMembers {
    /**
     * Resolves an agent token.
     *
     * @param principal An agent token's principal.
     * @param orgId The organization the request names, or null for the token's own.
     *
     * @return Its member and launch, or null when the principal is not a live agent token of that organization (its
     *   launch expired, its execution ended, or its person may no longer launch).
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): AgentMember?
}
