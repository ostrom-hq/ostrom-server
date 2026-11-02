// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.launches.domain.services.LauncherResolver

/**
 * The server's [GateActorResolver], which the gates, sessions and compiler routes resolve their callers with (it
 * replaces the gates module's fail-closed default):
 * - a person's credential (CLI, personal token, browser session) is a [ActorKind.USER] of the organization the orgs
 *   module resolves, named by their login, the `dev` of their sessions and the asker of their gates; whether it may
 *   say yes is the answer route's (S2-31: the browser or the app only);
 * - an agent token is the [ActorKind.AGENT_SESSION] of its launch, read in the token's own organization: its person
 *   is the launch's, and it acts only on that launch's sessions and gates (audit A12), until the launch expires or
 *   its person may no longer launch;
 * - a device or a service key belongs to no organization here.
 *
 * @param launchers Resolves a person and their organization.
 * @param agents Reads an agent token ([LaunchAgents]).
 */
class LaunchGateActors(private val launchers: LauncherResolver, private val agents: LaunchAgents) : GateActorResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = when (principal.kind) {
        PrincipalKind.AGENT -> agent(principal)
        PrincipalKind.USER -> launchers.resolve(principal)?.let {
            val signedIn = principal.authenticatedAt
            GateActor(it.login, it.orgId, ActorKind.USER, personId = it.login, authenticatedAt = signedIn)
        }
        PrincipalKind.DEVICE, PrincipalKind.SERVICE -> null
    }

    private suspend fun agent(principal: AuthenticatedPrincipal): GateActor? =
        agents.resolve(principal, null)?.let { (launch) ->
            GateActor(
                "agent-${launch.id}",
                launch.orgId,
                ActorKind.AGENT_SESSION,
                launchId = launch.id,
                personId = launch.dev,
            )
        }
}
