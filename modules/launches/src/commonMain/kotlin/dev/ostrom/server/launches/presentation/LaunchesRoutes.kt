// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.models.NewLaunch
import dev.ostrom.server.launches.domain.models.NoLaunchOrganizationException
import dev.ostrom.server.launches.domain.services.LauncherResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The launches routes of the contract. Both are a person's, through the CLI (its device token holds `sessions:write`)
 * or the console: an agent token never creates a launch nor reads the policy. The person and the organization always
 * come from the credential.
 *
 * @param dependencies The use cases.
 */
fun Route.launchesRoutes(dependencies: LaunchesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<AgentPolicyApi> { params ->
            val verdict = checkPolicy(call.launcher(launchers), params.agent, params.model)
            call.respond(AgentPolicyResponse(verdict.allowed, verdict.reason))
        }
        post<LaunchesApi> {
            val launcher = call.launcher(launchers)
            val body = call.receive<CreateLaunchRequest>()
            val created = createLaunch(launcher, NewLaunch(body.repo, body.agent, body.model))
            val launch = created.launch
            call.respond(
                HttpStatusCode.Created,
                LaunchResponse(launch.id, created.agentToken, launch.expiresAt.toString()),
            )
        }
    }
}

// The scope first (SECURITY-AUDIT S2-03): a credential that may not launch learns nothing about its organization.
private suspend fun ApplicationCall.launcher(launchers: LauncherResolver): Launcher {
    val principal = requireScope(LAUNCH_SCOPE)
    // Only a person resolves: an agent, a device or a service is never a launcher (audit A2).
    return launchers.resolve(principal) ?: throw NoLaunchOrganizationException()
}

/** The scope that launches an agent: the CLI's, and every browser session's. */
private const val LAUNCH_SCOPE = "sessions:write"
