// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireWrite
import dev.ostrom.server.runners.domain.CloudRunner
import dev.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import dev.ostrom.server.runners.domain.ListedRunner
import dev.ostrom.server.runners.domain.MachineExecutionState
import dev.ostrom.server.runners.domain.MachineReport
import dev.ostrom.server.runners.domain.MachineWork
import dev.ostrom.server.runners.domain.NewRunner
import dev.ostrom.server.runners.domain.RunnerNotFoundException
import dev.ostrom.server.runners.domain.RunnerRegistration
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import dev.ostrom.server.runners.domain.requireAdmin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The runners routes, for the console's Cloud screen:
 * - `GET /api/v1/runners`, with the scope that reads cloud sessions: the organization's registered runners, by name,
 *   then the runners at work for the caller's cloud sessions;
 * - `POST /api/v1/runners`, from a signed-in console session (it mints a long-lived key, CONVENTIONS 16.2) of an owner
 *   or an admin, with the scope that writes cloud sessions: registers a runner and returns its key, this once, in an
 *   answer no cache keeps (`Cache-Control: no-store`, S2-08); the role is checked before the body is read;
 * - `DELETE /api/v1/runners/{id}`, from the same session and role: removes a registered runner and its key; a runner of
 *   another organization, or a malformed id, is not found (404);
 * - `POST /api/v1/runners/{id}/heartbeat`, with the runner's key as a bearer token: marks it seen and returns its
 *   assigned sessions.
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
            caller.requireAdmin()
            val request = call.receive<RegisterRunnerRequest>()
            val registered = registerRunner(caller, RunnerRegistration(request.name, request.labels, request.version))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.Created, registered.toDto())
        }
        delete<RunnersApi.Id> { params ->
            val principal = call.requirePrincipal()
            principal.requireWrite()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            deleteRunner(caller, Uuid.parseOrNull(params.id) ?: throw RunnerNotFoundException())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    // Authenticated by the runner's own key, not a member's credential: an unknown id, a malformed one or a wrong key
    // all answer the same 401. The report is optional: a runner that runs no session sends no body, and is given none.
    post<RunnersApi.Id.Heartbeat> { params ->
        val key = call.runnerToken()
        val id = Uuid.parseOrNull(params.parent.id) ?: throw RunnerUnauthenticatedException()
        val json = call.request.contentType().match(ContentType.Application.Json)
        val report = if (json) call.receive<RegisteredRunnerHeartbeatRequest>().toReport() else null
        val work = heartbeat(id, key, report)
        // A plan holds a runner token.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(work.toResponse())
    }
}

/** Reads a runner's report; a malformed session id or an unknown state is a bad request. */
private fun RegisteredRunnerHeartbeatRequest.toReport() = MachineReport(
    executions = executions.associate { reported ->
        val state = MachineExecutionState.of(reported.state)
            ?.takeIf { it != MachineExecutionState.MISSING }
            ?: throw InvalidCloudSessionRequestException("Unknown execution state: use pending, running or ended.")
        (sessionIdOf(reported.sessionId) to reported.epoch) to state
    },
    workspaces = workspaces.map(::sessionIdOf).toSet(),
    capacity = capacity?.also {
        if (it !in 0..MAX_CAPACITY) {
            throw InvalidCloudSessionRequestException("The capacity is 0 to $MAX_CAPACITY sessions.")
        }
    },
)

/** The most sessions a registered runner may declare it runs at once. */
private const val MAX_CAPACITY = 256

private fun sessionIdOf(raw: String): Uuid =
    Uuid.parseOrNull(raw) ?: throw InvalidCloudSessionRequestException("Malformed session id.")

/** Maps a runner's work to the contract. */
private fun MachineWork.toResponse() = RegisteredRunnerHeartbeatResponse(
    sessions = emptyList(),
    executions = executions.map { assigned ->
        AssignedExecutionDto(
            assigned.sessionId.toString(),
            assigned.epoch,
            assigned.plan?.let { plan ->
                ExecutionPlanDto(
                    plan.volumes.map { PlannedObjectDto(it.name, it.body) },
                    plan.containers.map { PlannedObjectDto(it.name, it.body) },
                )
            },
        )
    },
    workspaces = workspaces.map { it.toString() },
)

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
