// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.kernel.auth.AgentMembers
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgProfile
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.uuid.Uuid

/**
 * The member an artifacts request acts for (TC-ART-14, 38). An agent token is the member who launched it, read by
 * [agents] (the launches module's reading, the one the gates and the MCP relay use), as TeamCode's execution
 * credential acts for the member its execution runs for: it publishes and shares as them, an administrator's agent
 * where only administrators publish too, and, presenting no person, it never makes an artifact public, deletes one,
 * reads the usage or changes the bucket. Every other credential is [orgs]'s. Without [agents] (no launches module), no
 * agent token is anyone here.
 *
 * @param orgs The kernel's resolver, for every credential but an agent token.
 * @param agents Reads an agent token, or null when no module binds it.
 */
class ArtifactOrgResolver(
    private val orgs: PrincipalOrgResolver,
    private val agents: AgentMembers?,
) : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        if (principal.kind == PrincipalKind.AGENT) {
            agents?.resolve(principal, orgId)?.member
        } else {
            orgs.resolve(principal, orgId)
        }

    override suspend fun profile(orgId: Uuid): OrgProfile? = orgs.profile(orgId)
}
