// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import kotlin.uuid.Uuid

/**
 * The server's [CallerResolver], which the routes of the MCP relay, the inference gateway and the budgets resolve their
 * callers with (it replaces their default, which only knows people):
 * - a person's credential is the member the orgs module resolves, with their role;
 * - an agent token is the member who launched it, read by [LaunchAgents] like the gates read it (its own organization,
 *   a live launch, a person who may still launch). It acts as a plain member whatever that person's role: an agent
 *   never administers the organization, and its single scope already keeps it off the console's routes. Its launch
 *   rides along ([Caller.launchId]), for the journal and the usage events, and so does the workflow it plays
 *   ([Caller.workflowId]), for the budgets and the spending of that workflow.
 *
 * @param orgs The orgs module's membership check.
 * @param agents Reads an agent token.
 */
class LaunchRelayCallers(orgs: PrincipalOrgResolver, private val agents: LaunchAgents) : CallerResolver {
    private val people = OrgPrincipalCallerResolver(orgs)

    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
        if (principal.kind == PrincipalKind.AGENT) {
            agents.resolve(principal, orgId)?.let {
                Caller(
                    it.member.orgId,
                    it.member.memberId,
                    MemberRole.MEMBER,
                    launchId = it.launch.id,
                    workflowId = it.launch.workflowId,
                )
            }
        } else {
            people.resolve(principal, orgId)
        }
}
