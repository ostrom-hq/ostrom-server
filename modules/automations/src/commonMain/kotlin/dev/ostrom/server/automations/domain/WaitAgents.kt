// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/**
 * The member an agent token asks for in the waits MCP server: the person who launched it, in the launch's
 * organization (PARCOURS 4.3: an agent asks a human through Ostrom's own MCP server).
 *
 * @property orgId The launch's organization.
 * @property memberId The member who launched it.
 * @property sessionId The segment of its session open now, whichever of the session's ids the agent named: its waits
 *   are asked there.
 */
data class WaitAgent(val orgId: Uuid, val memberId: String, val sessionId: String)

/**
 * Reads an agent token for the waits MCP server. The launches module binds it, with the one reading of an agent token
 * the gates and the MCP relay use; until then no agent token is anyone here (fail closed).
 */
interface WaitAgentResolver {
    /**
     * Resolves an agent token inside one of its launch's sessions (audit A12).
     *
     * @param principal An agent token's principal ([dev.ostrom.server.kernel.auth.PrincipalKind.AGENT]).
     * @param sessionId The session the request names: any of its segments, the one open now is found.
     *
     * @return The member who launched it and the open segment, or null when the principal is not a live agent token
     *   (its launch expired, its execution ended, or its person may no longer launch).
     *
     * @throws dev.ostrom.server.kernel.errors.OstromException When the session is unknown, its open segment another
     *   launch's, or none is open: the refusal the gates give an agent token in the same case.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent?
}
