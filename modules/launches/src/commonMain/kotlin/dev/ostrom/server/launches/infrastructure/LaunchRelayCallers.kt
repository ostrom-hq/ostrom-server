// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The server's [CallerResolver], which the routes of the MCP relay, the inference gateway and the budgets resolve their
 * callers with (it replaces their default, which only knows people):
 * - a person's credential is the member the orgs module resolves, with their role;
 * - an agent token is the member who launched it, in the token's own organization, while its launch lives and its
 *   person may still launch (as [LaunchGateActors] reads it). It acts as a plain member whatever that person's role:
 *   an agent never administers the organization, and its single scope already keeps it off the console's routes.
 *
 * @param orgs The orgs module's membership check.
 * @param launches The launches.
 * @param clock The clock.
 */
class LaunchRelayCallers(
    private val orgs: PrincipalOrgResolver,
    private val launches: LaunchesRepository,
    private val clock: Clock,
) : CallerResolver {
    private val people = OrgPrincipalCallerResolver(orgs)

    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
        if (principal.kind == PrincipalKind.AGENT) agent(principal, orgId) else people.resolve(principal, orgId)

    private suspend fun agent(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? {
        // A token bound to one organization never acts in another.
        val bound = principal.orgId?.let(Uuid::parseOrNull)?.takeIf { orgId == null || it == orgId }
        val launch = bound?.let { launches.find(it, principal.subjectId) }
        val live = launch?.takeIf { it.expiresAt > clock.now() }
        // The person as they are now: a member removed (or made billing only) stops their agents at once.
        val member = live?.let { orgs.resolve(it.person(), it.orgId) }?.takeIf { LaunchPolicy.launches(it.role) }
        return member?.let { Caller(it.orgId, it.memberId, MemberRole.MEMBER) }
    }

    private fun Launch.person() =
        AuthenticatedPrincipal(userId.toString(), PrincipalKind.USER, emptySet(), orgId = orgId.toString())
}
