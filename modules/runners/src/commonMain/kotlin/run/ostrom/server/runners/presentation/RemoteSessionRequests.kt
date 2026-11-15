// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.core.api.Idempotency
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.InvalidQueryException
import run.ostrom.server.runners.domain.KeyedCreation
import run.ostrom.server.runners.domain.LocalSessionDraft
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

// The creation of a session of any kind, and the host of a remote or local one (TeamCode's CloudRoutes kind switch).

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
            // The launch is the key of a local session: the Idempotency-Key is not read.
            SessionKind.LOCAL.wire -> createLocalSession(caller, principal, request.toLocalDraft())
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
    RemoteSessionRules.invalid("invalid_kind", "Use kind cloud, remote or local.")

/** A cloud session's creation, which takes none of a remote session's fields. */
private suspend fun CloudSessionsRoutesDependencies.createCloud(
    caller: Caller,
    request: CreateCloudSessionRequest,
    key: String?,
): KeyedCreation {
    val remote = with(request) {
        listOf(directoryName, permissionMode, deviceId, folderId, resumeSessionId, launchId)
    }
    if (remote.any { it != null }) {
        throw RemoteSessionRules.invalid(
            "invalid_session",
            "A cloud session runs in a pod the server starts and has no directory, permission mode, device or launch.",
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
    if (listOf(repo, ref, environment, profileId, image, launchId).any { it != null }) {
        throw RemoteSessionRules.invalid(
            "invalid_session",
            "A remote session has no repository, environment, profile, image or launch: it runs in a directory of " +
                "the caller's device.",
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
 * A local session's draft (TeamCode's `kind: local` branch): it is attached to a launch and has nothing else to say.
 *
 * @throws RemoteSessionException `invalid_session` or `invalid_launch` (400) otherwise.
 */
private fun CreateCloudSessionRequest.toLocalDraft(): LocalSessionDraft {
    val other = listOf(repo, ref, environment, profileId, image, directoryName, permissionMode, deviceId, folderId)
    if ((other + listOf(resumeSessionId, prompt)).any { it != null }) {
        throw RemoteSessionRules.invalid(
            "invalid_session",
            "A local session has no repository, environment, profile, image, directory, device, permission mode or " +
                "prompt: it is attached to an ostrom launch.",
        )
    }
    val launch = launchId ?: throw RemoteSessionRules.invalid("invalid_launch", "Send the launchId of your launch.")
    return LocalSessionDraft(launchId = launch, name = name, agent = agent)
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

