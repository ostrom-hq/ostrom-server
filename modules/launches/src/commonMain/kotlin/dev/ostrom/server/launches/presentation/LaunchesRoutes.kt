// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.presentation

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.launches.domain.models.CreatedLaunch
import dev.ostrom.server.launches.domain.models.InvalidLaunchException
import dev.ostrom.server.launches.domain.models.LaunchRateLimitedException
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.models.NewLaunch
import dev.ostrom.server.launches.domain.models.NoLaunchOrganizationException
import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The launches routes of the contract. Reading the policy and creating a launch are a person's, through the CLI (its
 * device token holds `sessions:write`) or the console: an agent token never creates a launch nor reads the policy. It
 * only ends its own launch, when `ostrom <agent>` ends. The person and the organization always
 * come from the credential. The runner of a cloud session opens its session's launch with its runner token instead.
 *
 * @param dependencies The use cases.
 */
fun Route.launchesRoutes(dependencies: LaunchesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<AgentPolicyApi> { params ->
            val verdict = checkPolicy(call.launcher(launchers), params.agent, params.model, params.dev)
            call.respond(AgentPolicyResponse(verdict.allowed, verdict.reason, verdict.note))
        }
        post<LaunchesApi> {
            val launcher = call.launcher(launchers)
            val body = call.receive<CreateLaunchRequest>()
            val created = call.retryAfter { createLaunch(launcher, NewLaunch(body.repo, body.agent, body.model)) }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }
        // The end of `ostrom <agent>` (AUDIT-SECU-RUNNER-2 R2b): only the launch's own agent token ends it.
        delete<LaunchApi> { params ->
            val caller = actors.resolve(call.requirePrincipal())?.takeIf { it.kind == ActorKind.AGENT_SESSION }
            endLaunch(caller?.launchId, params.launchId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    // The runner channel: the execution's runner token, checked before the body is read (S2-46), never a member's.
    post<RunnerLaunchApi> {
        val token = call.runnerToken()
        checkRunnerToken(token)
        val body = call.receive<RunnerLaunchRequest>()
        val sessionId = Uuid.parseOrNull(body.sessionId) ?: throw InvalidLaunchException("sessionId: a session's id.")
        val session = authenticateRunner(token, sessionId, body.epoch)
        call.respond(HttpStatusCode.Created, call.retryAfter { openRunnerLaunch(session) }.toResponse())
    }
}

// AUDIT-SECU-2 B9: a launch refused by the limit says when to retry; the error itself stays the uniform answer.
private suspend fun ApplicationCall.retryAfter(create: suspend () -> CreatedLaunch): CreatedLaunch = try {
    create()
} catch (limited: LaunchRateLimitedException) {
    response.header(HttpHeaders.RetryAfter, (limited.retryAfter.inWholeSeconds + 1).toString())
    throw limited
}

private fun CreatedLaunch.toResponse() = LaunchResponse(launch.id, agentToken, launch.expiresAt.toString())

// The scope first (SECURITY-AUDIT S2-03): a credential that may not launch learns nothing about its organization.
private suspend fun ApplicationCall.launcher(launchers: LauncherResolver): Launcher {
    val principal = requireScope(LAUNCH_SCOPE)
    // Only a person resolves: an agent, a device or a service is never a launcher (audit A2).
    return launchers.resolve(principal) ?: throw NoLaunchOrganizationException()
}

/** The scope that launches an agent: the CLI's, and every browser session's. */
private const val LAUNCH_SCOPE = "sessions:write"

/**
 * The runner token of a runner call, `Authorization: Bearer ostrom_runner_…`, read as the runners module reads it
 * (a module never imports another one's presentation).
 *
 * @throws RunnerUnauthenticatedException When absent.
 */
private fun ApplicationCall.runnerToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw RunnerUnauthenticatedException()
    val token = header.removePrefix("Bearer ").trim()
    if (token == header || token.isEmpty()) throw RunnerUnauthenticatedException()
    return token
}
