// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DaemonPresence
import run.ostrom.server.kernel.auth.OwnedDevice
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.DaemonOrder
import run.ostrom.server.runners.domain.DeviceDaemonRules
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.ResumableAnswer
import run.ostrom.server.runners.domain.ResumableSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The largest folder list a daemon publishes: 64 folders of a 512-character label at most. */
private const val FOLDERS_BODY_LIMIT = 128L * 1024

/** The largest listing answer: 50 conversations of an 80-character title. */
private const val LISTING_BODY_LIMIT = 64L * 1024

/** The largest poll or refusal. */
private const val SMALL_BODY_LIMIT = 4L * 1024

/**
 * The daemon channel and the owner's view of a device's folders (TeamCode's `deviceDaemonRoutes`,
 * `runners.cloud.remote.daemon.v1`). Every route takes a person's credential in the organization the request names; the
 * daemon's routes also require it to be a CLI sign-in bound to a device (`422 daemon_requires_device` otherwise), and
 * act on that device only. Titles are never logged: a listing's body is read here and handed to the use case, and
 * failures name only a code.
 *
 * @param dependencies The use cases.
 */
fun Route.deviceDaemonRoutes(dependencies: DeviceDaemonRoutesDependencies) = with(dependencies) {
    authenticated {
        bodyLimit(SMALL_BODY_LIMIT) {
            post<DeviceDaemonApi.CurrentDevice.CurrentDaemon.DaemonPoll> {
                val principal = call.requirePrincipal()
                val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
                val input = call.receive<DaemonPollRequest>()
                val poll = daemon.poll(caller, principal, input.waitMillis.milliseconds, input.version, input.features)
                call.respond(
                    DaemonPollResponse(
                        orders = poll.orders.map { it.toDto() },
                        onlineTtlMillis = DaemonPresence.ONLINE_TTL.inWholeMilliseconds,
                        maxWaitMillis = DeviceDaemonRules.MAX_WAIT.inWholeMilliseconds,
                        listings = poll.listings.map {
                            DaemonListingDto(it.requestId.toString(), it.folderId, it.limit, it.before)
                        },
                    ),
                )
            }
            delete<DeviceDaemonApi.CurrentDevice.CurrentDaemon> {
                val principal = call.requirePrincipal()
                orgResolver.callerOf(principal, CLOUD_WRITE)
                daemon.offline(principal)
                call.respond(HttpStatusCode.NoContent)
            }
            post<DeviceDaemonApi.CurrentDevice.CurrentDaemon.DaemonRefusal> { params ->
                val principal = call.requirePrincipal()
                val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
                val id = Uuid.parseOrNull(params.id) ?: throw CloudSessionNotFoundException()
                val input = call.receive<RefuseDaemonOrderRequest>()
                val session = refuseHost(caller, daemon.device(principal), id, input.reason)
                call.respond(hosts.response(session))
            }
        }
        daemonAnswers(dependencies)
        ownerRoutes(dependencies)
    }
}

/** What the daemon sends: its folders and its listings' answers, each under its own body limit. */
private fun Route.daemonAnswers(dependencies: DeviceDaemonRoutesDependencies) = with(dependencies) {
    bodyLimit(FOLDERS_BODY_LIMIT) {
        put<DeviceDaemonApi.CurrentDevice.PublishedFolders> {
            val principal = call.requirePrincipal()
            orgResolver.callerOf(principal, CLOUD_WRITE)
            val input = call.receive<PublishDeviceFoldersRequest>()
            val folders = daemon.publish(principal, input.folders.map { DaemonFolder(it.id, it.name, it.label) })
            call.respond(folders.map { it.toDto() })
        }
    }
    bodyLimit(LISTING_BODY_LIMIT) {
        post<DeviceDaemonApi.CurrentDevice.CurrentDaemon.DaemonListing> { params ->
            val principal = call.requirePrincipal()
            orgResolver.callerOf(principal, CLOUD_WRITE)
            val id = Uuid.parseOrNull(params.id) ?: throw listingNotFound()
            val input = call.receive<DaemonListingAnswerRequest>()
            daemon.answer(principal, id, input.toAnswer())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The owner's view of one of their devices. */
private fun Route.ownerRoutes(dependencies: DeviceDaemonRoutesDependencies) = with(dependencies) {
    get<DeviceDaemonApi.DeviceFolders> { params ->
        val principal = call.requirePrincipal()
        orgResolver.callerOf(principal, CLOUD_READ)
        val id = Uuid.parseOrNull(params.id) ?: throw DeviceDaemonRules.deviceNotFound()
        val device = daemon.owned(principal, id)
        call.respond(device.toDto())
    }
    get<DeviceDaemonApi.DeviceFolders.Resumable> { params ->
        val principal = call.requirePrincipal()
        orgResolver.callerOf(principal, CLOUD_READ)
        val id = Uuid.parseOrNull(params.parent.id) ?: throw DeviceDaemonRules.deviceNotFound()
        val folderId = params.folderId.takeIf(DeviceDaemonRules::validFolderId)
            ?: throw DeviceDaemonRules.folderNotPublished("This device")
        val limit = params.limit?.let {
            it.toIntOrNull() ?: throw RemoteSessionRules.invalid(
                "invalid_limit",
                "Use a limit from 1 to ${DeviceDaemonRules.MAX_LISTING_LIMIT}.",
            )
        } ?: DeviceDaemonRules.DEFAULT_LISTING_LIMIT
        val answer = daemon.resumable(principal, id, folderId, limit, params.before)
        call.respond(
            ResumableSessionPageResponse(
                answer.sessions.map { ResumableSessionDto(it.id, it.title, it.lastActiveAt.toString(), it.turns) },
                answer.next,
            ),
        )
    }
}

private fun DaemonOrder.toDto() = DaemonOrderDto(
    sessionId = sessionId.toString(),
    folderId = folderId,
    directoryName = directoryName,
    agent = agent,
    permissionMode = permissionMode,
    status = status,
    requestedAt = requestedAt.toString(),
    resumeSessionId = resumeSessionId,
)

private fun DaemonFolder.toDto() = DeviceFolderDto(id, name, label)

private fun OwnedDevice.toDto() = DeviceFoldersResponse(id.toString(), daemon?.toDto(), folders.map { it.toDto() })

/** A device's daemon in the API. */
internal fun DaemonPresence.toDto() = DaemonPresenceDto(online, lastSeenAt.toString(), version, features)

/** The daemon's answer; a conversation's last activity that is not a time is `invalid_listing`. */
private fun DaemonListingAnswerRequest.toAnswer(): ResumableAnswer = ResumableAnswer(
    items.map {
        val at = Instant.parseOrNull(it.lastActiveAt)
            ?: throw RemoteSessionRules.invalid("invalid_listing", "Send each conversation's last activity as a time.")
        ResumableSession(it.id, it.title, at, it.turns)
    },
    next,
    refused,
)

private fun listingNotFound() =
    RemoteSessionException(ErrorKind.NOT_FOUND, "listing_not_found", "Nobody waits for this listing any more.")
