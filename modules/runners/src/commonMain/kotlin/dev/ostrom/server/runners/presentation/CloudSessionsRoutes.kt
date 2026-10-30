package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.CloudSessionNotFoundException
import dev.ostrom.server.runners.domain.CreateCloudSessionPayload
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCase
import dev.ostrom.server.runners.domain.GetCloudSessionUseCase
import dev.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCase
import dev.ostrom.server.runners.domain.RepositorySpec
import dev.ostrom.server.runners.domain.RunnerHeartbeat
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the cloud session routes need.
 *
 * @property createCloudSession Creates a session.
 * @property getCloudSession Reads one.
 * @property listCloudSessions Lists the caller's.
 * @property changeCloudSession Pauses, resumes, deletes.
 * @property runnerHeartbeat The runner channel.
 */
data class CloudSessionsRoutesDependencies(
    val createCloudSession: CreateCloudSessionUseCase,
    val getCloudSession: GetCloudSessionUseCase,
    val listCloudSessions: ListCloudSessionsUseCase,
    val changeCloudSession: ChangeCloudSessionUseCase,
    val runnerHeartbeat: RunnerHeartbeatUseCase,
)

/**
 * The cloud session routes: management under `/v1/cloud/sessions` for members, and the runner channel under
 * `/v1/runner`, authenticated by the execution's runner token instead of a member's token.
 *
 * @param dependencies The use cases.
 */
fun Route.cloudSessionsRoutes(dependencies: CloudSessionsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<CloudSessionsApi> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_READ)
            val sessions = listCloudSessions(caller, params.before?.let { parseId(it) }, params.limit)
            val limit = params.limit ?: DEFAULT_LIMIT
            call.respond(
                CloudSessionsResponse(
                    sessions = sessions.map { it.toResponse() },
                    nextBefore = sessions.lastOrNull()?.takeIf { sessions.size == limit }?.id?.toString(),
                ),
            )
        }
        post<CloudSessionsApi> {
            val caller = call.requirePrincipal().toCaller(CLOUD_WRITE)
            val request = call.receive<CreateCloudSessionRequest>()
            val payload = CreateCloudSessionPayload(
                name = request.name,
                agent = request.agent,
                repository = request.repository?.let { RepositorySpec(it.url, it.ref) },
            )
            call.respond(HttpStatusCode.Created, createCloudSession(caller, payload).toResponse())
        }
        get<CloudSessionsApi.Id> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_READ)
            call.respond(getCloudSession(caller, sessionId(params.id)).toResponse())
        }
        delete<CloudSessionsApi.Id> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_WRITE)
            call.respond(HttpStatusCode.Accepted, changeCloudSession.delete(caller, sessionId(params.id)).toResponse())
        }
        post<CloudSessionsApi.Id.Pause> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_WRITE)
            call.respond(HttpStatusCode.Accepted, changeCloudSession.pause(caller, sessionId(params.parent.id)).toResponse())
        }
        post<CloudSessionsApi.Id.Resume> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_WRITE)
            call.respond(HttpStatusCode.Accepted, changeCloudSession.resume(caller, sessionId(params.parent.id)).toResponse())
        }
    }
    post<RunnerHeartbeatApi> {
        val token = call.runnerToken()
        val request = call.receive<RunnerHeartbeatRequest>()
        val heartbeat = RunnerHeartbeat(
            sessionId = parseId(request.sessionId),
            epoch = request.epoch,
            ready = request.ready,
            stopped = request.stopped,
            lastModelRequestAt = request.lastModelRequestAt?.let { parseInstant(it) },
        )
        val answer = runnerHeartbeat(token, heartbeat)
        call.respond(RunnerHeartbeatResponse(answer.instruction.wire, answer.state.wire))
    }
}

private const val DEFAULT_LIMIT = 20

/** A malformed id names no session: 404, like an unknown one. */
private fun sessionId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw CloudSessionNotFoundException()

private fun parseId(raw: String): Uuid =
    Uuid.parseOrNull(raw) ?: throw InvalidCloudSessionRequestException("Malformed session id.")

private fun parseInstant(raw: String): Instant = try {
    Instant.parse(raw)
} catch (_: IllegalArgumentException) {
    throw InvalidCloudSessionRequestException("Malformed timestamp: use ISO 8601.")
}

/**
 * The runner token of a runner call: `Authorization: Bearer ostrom_runner_…`.
 *
 * @throws RunnerUnauthenticatedException When absent.
 */
internal fun ApplicationCall.runnerToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw RunnerUnauthenticatedException()
    val token = header.removePrefix("Bearer ").trim()
    if (token == header || token.isEmpty()) throw RunnerUnauthenticatedException()
    return token
}

/** Maps a session to the API. */
fun CloudSession.toResponse() = CloudSessionResponse(
    id = id.toString(),
    name = name,
    ownerId = ownerId,
    agent = agent.wire,
    repository = repository?.let { RepositoryDto(it.url, it.ref) },
    state = state.wire,
    desiredState = desiredState.wire,
    phase = phase,
    epoch = epoch,
    failure = failure?.let { SessionFailureDto(it.code, it.message) },
    limits = SessionLimitsDto(limits.idleTimeoutMinutes, limits.maxRunMinutes, limits.restrictedEgress),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    readyAt = readyAt?.toString(),
    lastActivityAt = lastActivityAt?.toString(),
    pausedAt = pausedAt?.toString(),
    terminatedAt = terminatedAt?.toString(),
)
