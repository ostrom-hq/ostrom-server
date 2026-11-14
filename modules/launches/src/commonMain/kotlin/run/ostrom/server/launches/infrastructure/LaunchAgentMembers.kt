// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.auth.AgentMember
import run.ostrom.server.kernel.auth.AgentMembers
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.uuid.Uuid

/**
 * The server's [AgentMembers] (the artifacts module reads an agent token with it): only an agent token is read, by
 * [LaunchAgents], like the gates and the MCP relay read it (its own organization, a live launch, a person who may
 * still launch), and it is the person's membership as it is now, with their role.
 *
 * @param agents Reads an agent token.
 */
class LaunchAgentMembers(private val agents: LaunchAgents) : AgentMembers {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): AgentMember? {
        // A person's credential or a key never becomes an agent here.
        val found = if (principal.kind == PrincipalKind.AGENT) agents.resolve(principal, orgId) else null
        return found?.let { (launch, member) -> AgentMember(member, launch.id, launch.cloudSessionId?.toString()) }
    }
}
