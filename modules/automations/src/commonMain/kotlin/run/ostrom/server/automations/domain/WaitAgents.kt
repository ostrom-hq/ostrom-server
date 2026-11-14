// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/**
 * The member an agent token asks for in the waits MCP server: the person who launched it, in the launch's
 * organization (PARCOURS 4.3: an agent asks a human through Ostrom's own MCP server).
 *
 * @property orgId The launch's organization.
 * @property memberId The member who launched it.
 * @property sessionId The segment of its session open now, whichever of the session's ids the agent named: its waits
 *   are asked there. For a cloud session's launch, the cloud session's id, whatever the agent named.
 * @property segments The segments of its session its launch opened, [sessionId] included: the waits asked there are
 *   its own. Another launch that resumed the session has its own. For a cloud session's launch, the cloud session's id
 *   alone: every execution of the session asks and is woken there.
 */
data class WaitAgent(val orgId: Uuid, val memberId: String, val sessionId: String, val segments: List<String>)

/**
 * Reads an agent token for the waits MCP server. The launches module binds it, with the one reading of an agent token
 * the gates and the MCP relay use; until then no agent token is anyone here (fail closed).
 */
interface WaitAgentResolver {
    /**
     * Resolves an agent token inside one of its launch's sessions (audit A12).
     *
     * @param principal An agent token's principal ([run.ostrom.server.kernel.auth.PrincipalKind.AGENT]).
     * @param sessionId The session the request names: any of its segments, the one open now is found.
     *
     * @return The member who launched it and the open segment, or null when the principal is not a live agent token
     *   (its launch expired, its execution ended, or its person may no longer launch).
     *
     * @throws run.ostrom.server.kernel.errors.OstromException When the session is unknown, its open segment another
     *   launch's, or none is open: the refusal the gates give an agent token in the same case.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent?

    /**
     * Resolves an agent token in its session, which the request may leave out: a cloud session's launch acts on its
     * cloud session whatever the request names (TeamCode's execution credential `tcex_`, confined to the cloud session
     * it was issued for), a launch on a machine in the session it names. Without an override, a token must name one.
     *
     * @param principal An agent token's principal.
     * @param sessionId The session the request names, or null.
     * @param missing The refusal of a token that must name its session and names none.
     *
     * @return As [resolve].
     *
     * @throws OstromException [missing], or as [resolve].
     */
    suspend fun resolve(
        principal: AuthenticatedPrincipal,
        sessionId: String?,
        missing: () -> OstromException,
    ): WaitAgent? = resolve(principal, sessionId ?: throw missing())
}
