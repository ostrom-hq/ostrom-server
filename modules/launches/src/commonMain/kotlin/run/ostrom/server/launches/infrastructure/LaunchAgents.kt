// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.domain.services.CloudExecutions
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.launches.domain.usecases.LaunchPolicy
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * An agent token's launch, and the membership of the person who launched it as it is now.
 *
 * @property launch The launch.
 * @property member The person's membership of the launch's organization.
 */
data class LaunchAgent(val launch: Launch, val member: OrgPrincipal)

/**
 * The one reading of an agent token, for every route that accepts one ([LaunchGateActors] for the gates, sessions and
 * compiler; [LaunchRelayCallers] for the MCP relay, the inference gateway, the budgets and the usage events): the
 * token is the launch it names, read in the token's own organization, until the launch expires or its person may no
 * longer launch (removed, or made billing only: their agents stop at once, like their own credentials). The launch of
 * a cloud session lives only as long as the execution its runner opened it in (AUDIT-SECU-RUNNER-2 R2).
 *
 * @param launchers Reads a person's membership now.
 * @param launches The launches.
 * @param clock The clock.
 * @param executions Tells whether a cloud session's execution is still the one a launch was opened in.
 */
class LaunchAgents(
    private val launchers: LauncherResolver,
    private val launches: LaunchesRepository,
    private val clock: Clock,
    private val executions: CloudExecutions,
) {
    /**
     * Resolves an agent token.
     *
     * @param principal An agent token's principal ([run.ostrom.server.kernel.auth.PrincipalKind.AGENT]): its subject
     *   is its launch.
     * @param orgId The organization the request names, or null for the token's own.
     *
     * @return The launch and its person's membership, or null when the principal is not a live agent token of that
     *   organization.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): LaunchAgent? {
        // A token bound to one organization never acts in another.
        val bound = principal.orgId?.let(Uuid::parseOrNull)?.takeIf { orgId == null || it == orgId }
        val found = bound?.let { launches.find(it, principal.subjectId) }?.takeIf { it.expiresAt > clock.now() }
        val live = found?.takeIf { running(it) }
        return live?.let { launch ->
            val member = launchers.member(launch.orgId, launch.userId)?.takeIf { LaunchPolicy.launches(it.role) }
            member?.let { LaunchAgent(launch, it) }
        }
    }

    // A launch of the CLI lives until it expires; a cloud session's, while the execution it was opened in runs.
    private suspend fun running(launch: Launch): Boolean {
        val cloud = launch.cloudSessionId ?: return true
        return executions.current(launch.orgId, cloud, launch.createdAt)
    }
}
