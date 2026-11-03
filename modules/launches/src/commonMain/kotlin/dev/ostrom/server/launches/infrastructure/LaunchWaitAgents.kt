// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.automations.domain.WaitAgent
import dev.ostrom.server.automations.domain.WaitAgentResolver
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal

/**
 * The server's [WaitAgentResolver], which the waits MCP server resolves an agent token with (it replaces the
 * automations module's fail-closed default), so that an agent asks a person through Ostrom's own MCP server
 * (PARCOURS 4.3):
 * - the token is read by [LaunchAgents], like the gates and the MCP relay read it: its own organization, a live launch,
 *   a person who may still launch;
 * - it acts only inside one of its launch's open sessions, checked by the sessions module's guard the gates use for an
 *   agent token ([GateSessions.assertOpen], audit A12): another launch's session is refused, an unknown one is not
 *   found, a closed one has a signed receipt nothing is added to.
 *
 * @param agents Reads an agent token.
 * @param sessions The sessions module's guard.
 */
class LaunchWaitAgents(private val agents: LaunchAgents, private val sessions: GateSessions) : WaitAgentResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
        val (launch, member) = agents.resolve(principal, null) ?: return null
        sessions.assertOpen(launch.orgId, sessionId, launch.id)
        return WaitAgent(member.orgId, member.memberId.toString(), launch.id)
    }
}
