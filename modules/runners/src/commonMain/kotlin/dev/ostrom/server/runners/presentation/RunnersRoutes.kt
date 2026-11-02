// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.runners.domain.CloudRunner
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * `GET /api/v1/runners`, for the console's Cloud screen: the runners of the caller's cloud sessions, with the scope
 * that reads cloud sessions.
 *
 * @param dependencies The use case and the runtime.
 */
fun Route.runnersRoutes(dependencies: RunnersRoutesDependencies) = with(dependencies) {
    authenticated {
        get<RunnersApi> {
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            call.respond(RunnersPage(listRunners(caller).map { it.toDto(runtime) }))
        }
    }
}

/**
 * Maps a runner to the contract.
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
