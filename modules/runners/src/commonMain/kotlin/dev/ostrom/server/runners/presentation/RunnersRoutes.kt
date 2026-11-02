// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireWrite
import dev.ostrom.server.runners.domain.CloudRunner
import dev.ostrom.server.runners.domain.ListedRunner
import dev.ostrom.server.runners.domain.NewRunner
import dev.ostrom.server.runners.domain.RunnerRegistration
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The runners routes, for the console's Cloud screen:
 * - `GET /api/v1/runners`, with the scope that reads cloud sessions: the organization's registered runners, by name,
 *   then the runners at work for the caller's cloud sessions;
 * - `POST /api/v1/runners`, from a signed-in console session (it mints a long-lived key, CONVENTIONS 16.2) of an owner
 *   or an admin, with the scope that writes cloud sessions: registers a runner and returns its key, this once.
 *
 * @param dependencies The use cases and the runtime.
 */
fun Route.runnersRoutes(dependencies: RunnersRoutesDependencies) = with(dependencies) {
    authenticated {
        get<RunnersApi> {
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            val registered = listRegisteredRunners(caller).map { it.toDto() }
            call.respond(RunnersPage(registered + listRunners(caller).map { it.toDto(runtime) }))
        }
        post<RunnersApi> {
            val principal = call.requirePrincipal()
            principal.requireWrite()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val request = call.receive<RegisterRunnerRequest>()
            val registered = registerRunner(caller, RunnerRegistration(request.name, request.labels, request.version))
            call.respond(HttpStatusCode.Created, registered.toDto())
        }
    }
}

/**
 * Maps a runner at work to the contract.
 *
 * @param runtime Where it runs.
 * @return The DTO.
 */
fun CloudRunner.toDto(runtime: RunnerRuntime) = RunnerDto(
    id = sessionId.toString(),
    name = name,
    labels = listOfNotNull(runtime.driver, agent.wire),
    status = if (online) "online" else "offline",
    lastSeenAt = lastSeenAt?.toString(),
    version = runtime.version,
)

/** Maps a registered runner to the contract; its key never appears in a list. */
fun ListedRunner.toDto() = RunnerDto(
    id = runner.id.toString(),
    name = runner.name,
    labels = runner.labels,
    status = if (online) "online" else "offline",
    lastSeenAt = runner.lastSeenAt?.toString(),
    version = runner.version,
)

/** Maps a runner just registered to the contract, with its key. */
fun NewRunner.toDto() = RegisteredRunnerDto(
    id = runner.id.toString(),
    name = runner.name,
    labels = runner.labels,
    status = "offline",
    version = runner.version,
    token = key,
)
