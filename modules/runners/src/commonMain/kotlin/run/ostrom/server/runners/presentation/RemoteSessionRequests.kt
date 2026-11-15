// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.core.api.Idempotency
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.InvalidQueryException
import run.ostrom.server.runners.domain.KeyedCreation
import run.ostrom.server.runners.domain.RemoteSessionDraft
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

// The creation of a session of either kind, and the host of a remote one (TeamCode's CloudRoutes kind switch).

/** `POST /api/v1/cloud/sessions` and `POST /api/v1/cloud/sessions/{id}/host`, inside the member routes. */
internal fun Route.creationRoutes(dependencies: CloudSessionsRoutesDependencies) = with(dependencies) {
    post<CloudSessionsApi> {
        val caller = call.cloudActor(orgResolver, resolveActor)
        val principal = call.requirePrincipal()
        val request = call.receive<CreateCloudSessionRequest>()
        val key = call.request.header(Idempotency.KEY_HEADER)?.also {
            if (!Idempotency.isValid(it)) throw InvalidCloudSessionRequestException(MALFORMED_KEY)
        }
        val created = when (request.kind ?: SessionKind.CLOUD.wire) {
            SessionKind.CLOUD.wire -> createCloud(caller, request, key)
            SessionKind.REMOTE.wire -> {
                val keyed = key?.let { it to requestDigest(request, caller) }
                createRemoteSession(caller, principal, request.toRemoteDraft(), keyed)
            }
            else -> throw invalidKind()
        }
        if (created.replayed) call.response.header(Idempotency.REPLAYED_HEADER, "true")
        val status = if (created.replayed) HttpStatusCode.Accepted else HttpStatusCode.Created
        call.respond(status, hosts.response(created.session))
    }
    post<CloudSessionsApi.Id.Host> { params ->
        val principal = call.requirePrincipal()
        val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
        val id = Uuid.parseOrNull(params.parent.id) ?: throw CloudSessionNotFoundException()
        val host = hostRemoteSession(caller, principal, id)
        call.respond(CloudHostResponse(host.orgId.toString(), host.epoch, hosts.response(host.session)))
    }
}

private fun invalidKind(): RemoteSessionException =
    RemoteSessionRules.invalid("invalid_kind", "Use kind cloud or remote.")

/** A cloud session's creation, which takes none of a remote session's fields. */
private suspend fun CloudSessionsRoutesDependencies.createCloud(
    caller: Caller,
    request: CreateCloudSessionRequest,
    key: String?,
): KeyedCreation {
    val remote = with(request) { listOf(directoryName, permissionMode, deviceId, folderId, resumeSessionId) }
    if (remote.any { it != null }) {
        throw RemoteSessionRules.invalid(
            "invalid_session",
            "A cloud session runs in a pod the server starts and has no directory, permission mode or device.",
        )
    }
    val payload = request.toPayload()
    if (key == null) return KeyedCreation(createCloudSession(caller, payload), replayed = false)
    return createCloudSession.createKeyed(caller, payload, key, requestDigest(request, caller))
}

/**
 * A remote session's draft: it has no repository, recipe, profile or image, and always runs in a conversation.
 *
 * @throws RemoteSessionException `invalid_session` or `invalid_mode` (400) otherwise.
 */
private fun CreateCloudSessionRequest.toRemoteDraft(): RemoteSessionDraft {
    if (listOf(repo, ref, environment, profileId, image).any { it != null }) {
        throw RemoteSessionRules.invalid(
            "invalid_session",
            "A remote session has no repository, environment, profile or image: it runs in a directory of the " +
                "caller's device.",
        )
    }
    if (mode != null && mode != SessionMode.CONVERSATION.wire) {
        throw RemoteSessionRules.invalid("invalid_mode", "A remote session always runs in mode conversation.")
    }
    return RemoteSessionDraft(
        name = name,
        agent = agent,
        model = model,
        effort = effort,
        prompt = prompt,
        directoryName = directoryName,
        permissionMode = permissionMode,
        deviceId = deviceId,
        folderId = folderId,
        resumeSessionId = resumeSessionId,
    )
}

/**
 * The value of a query parameter read once (TeamCode's `single`).
 *
 * @param name The parameter.
 * @param values Its values.
 *
 * @return The value, or null when absent.
 *
 * @throws InvalidQueryException When it was sent several times.
 */
internal fun atMostOnce(name: String, values: List<String>): String? =
    if (values.size > 1) throw InvalidQueryException(name) else values.singleOrNull()

/**
 * The kinds a list asks for: cloud sessions only when it names none.
 *
 * @throws RemoteSessionException `invalid_kind` (400) for an unknown one.
 */
internal fun kinds(wires: List<String>): Set<SessionKind> =
    if (wires.isEmpty()) {
        setOf(SessionKind.CLOUD)
    } else {
        wires.map { SessionKind.fromWire(it) ?: throw invalidKind() }.toSet()
    }

