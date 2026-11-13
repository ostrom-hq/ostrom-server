// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind

/**
 * The server's [WaitAgentResolver], which the waits MCP server resolves an agent token with (it replaces the
 * automations module's fail-closed default), so that an agent asks a person through Ostrom's own MCP server
 * (PARCOURS 4.3):
 * - only an agent token is read, by [LaunchAgents], like the gates and the MCP relay read it: its own organization,
 *   a live launch, a person who may still launch;
 * - it acts only inside one of its launch's open sessions, checked by the sessions module's guard the gates use for an
 *   agent token (audit A12): another launch's session is refused, an unknown one is not found, a closed one has a
 *   signed receipt nothing is added to;
 * - the guard finds the segment open now from whichever id of the session the agent names
 *   ([GateSessions.openSegment]): the launcher may have reopened one, after the machine slept, without the relay
 *   knowing.
 *
 * @param agents Reads an agent token.
 * @param sessions The sessions module's guard.
 */
class LaunchWaitAgents(private val agents: LaunchAgents, private val sessions: GateSessions) : WaitAgentResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
        // Only an agent token is read as a launch: a person's credential or a key never becomes an agent here.
        val found = if (principal.kind == PrincipalKind.AGENT) agents.resolve(principal, null) else null
        return found?.let { (launch, member) ->
            val open = sessions.openSegment(launch.orgId, sessionId, launch.id)
            WaitAgent(member.orgId, member.memberId.toString(), open.id, open.launchSegments)
        }
    }
}
