// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.RunnerGitHubRequest
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerHeartbeat
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The cloud session routes: management under `/api/v1/cloud/sessions` for members, and the runner channel under
 * `/api/v1/cloud/runner`, authenticated by the execution's runner token instead of a member's token.
 *
 * @param dependencies The use cases.
 */
fun Route.cloudSessionsRoutes(dependencies: CloudSessionsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<CloudSessionsApi> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            val sessions = listCloudSessions(caller, params.before?.let { parseId(it) }, params.limit)
            val limit = params.limit ?: DEFAULT_LIMIT
            call.respond(
                CloudSessionsResponse(
                    items = sessions.map { it.toResponse() },
                    next = sessions.lastOrNull()?.let { last -> last.id.toString().takeIf { sessions.size == limit } },
                ),
            )
        }
        post<CloudSessionsApi> {
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_WRITE)
            val request = call.receive<CreateCloudSessionRequest>()
            val payload = CreateCloudSessionPayload(
                name = request.name,
                agent = request.agent,
                repository = request.repo?.let { RepositorySpec(it, request.ref) },
                model = request.model,
                prompt = request.prompt,
            )
            call.respond(HttpStatusCode.Created, createCloudSession(caller, payload).toResponse())
        }
        get<CloudSessionsApi.Id> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            call.respond(getCloudSession(caller, sessionId(params.id)).toResponse())
        }
        delete<CloudSessionsApi.Id> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_WRITE)
            call.respond(HttpStatusCode.Accepted, changeCloudSession.delete(caller, sessionId(params.id)).toResponse())
        }
        post<CloudSessionsApi.Id.Pause> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_WRITE)
            val paused = changeCloudSession.pause(caller, sessionId(params.parent.id))
            call.respond(HttpStatusCode.Accepted, paused.toResponse())
        }
        post<CloudSessionsApi.Id.Resume> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_WRITE)
            val resumed = changeCloudSession.resume(caller, sessionId(params.parent.id))
            call.respond(HttpStatusCode.Accepted, resumed.toResponse())
        }
        bodyLimit(ACCESS_BODY_LIMIT) {
            put<CloudSessionsApi.Id.GitHub> { params ->
                val principal = call.requirePrincipal()
                val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
                val request = call.receive<SetCloudSessionGitHubAccessRequest>()
                val session = setGitHubAccess(caller, principal, sessionId(params.parent.id), request.access)
                call.respond(session.toResponse())
            }
        }
    }
    runnerChannelRoutes(dependencies)
}

/** The runner channel, authenticated by the execution's runner token, checked before the body is read (S2-46). */
private fun Route.runnerChannelRoutes(dependencies: CloudSessionsRoutesDependencies) = with(dependencies) {
    bodyLimit(HEARTBEAT_BODY_LIMIT) {
        post<RunnerHeartbeatApi> {
            val token = call.runnerToken()
            // The token first, the body after (S2-46): an unknown token never makes the server read a byte.
            checkRunnerToken(token)
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
        // The owner's GitHub token for one Git or `gh` operation: never cached, never logged.
        post<RunnerGitHubApi> {
            val token = call.runnerToken()
            checkRunnerToken(token)
            val request = call.receive<RunnerGitHubTokenRequest>()
            val access = runnerGitHubToken(token, RunnerGitHubRequest(parseId(request.sessionId), request.epoch))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                when (access) {
                    is RunnerGitHubToken.Linked ->
                        RunnerGitHubTokenResponse(access.wire, access.token, access.expiresAt?.toString(), access.login)
                    else -> RunnerGitHubTokenResponse(access.wire)
                },
            )
        }
    }
}

/** A heartbeat is a few fields: 4 KiB is plenty (S2-46); a token request and an access change, fewer still. */
private const val HEARTBEAT_BODY_LIMIT = 4L * 1024
private const val ACCESS_BODY_LIMIT = 1024L

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
    owner = ownerId,
    agent = agent.wire,
    model = model,
    repo = repository?.url,
    ref = repository?.ref,
    status = state.wire,
    desiredStatus = desiredState.wire,
    phase = phase,
    epoch = epoch,
    failure = failure?.let { SessionFailureDto(it.code, it.message) },
    limits = SessionLimitsDto(limits.idleTimeoutMinutes, limits.maxRunMinutes, limits.restrictedEgress),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    readyAt = readyAt?.toString(),
    lastActivityAt = lastActivityAt?.toString(),
    pausedAt = pausedAt?.toString(),
    endedAt = terminatedAt?.toString(),
    runnerId = runnerId?.toString(),
    githubAccess = githubAccess.wire,
)
