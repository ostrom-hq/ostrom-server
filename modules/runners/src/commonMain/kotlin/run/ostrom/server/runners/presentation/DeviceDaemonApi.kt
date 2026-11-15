// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * The device daemon's routes (`runners.cloud.remote.daemon.v1`), as in ostrom-core's `api/openapi.yaml`: the daemon of
 * the caller's own device under `/api/v1/devices/current`, and a device's folders under `/api/v1/devices/{id}`.
 */
@Serializable
@Resource("/api/v1/devices")
class DeviceDaemonApi {
    /**
     * The device of the caller's sign-in.
     *
     * @property parent The devices.
     */
    @Serializable
    @Resource("current")
    class CurrentDevice(val parent: DeviceDaemonApi = DeviceDaemonApi()) {
        /**
         * `DELETE /api/v1/devices/current/daemon` (`stopDeviceDaemon`).
         *
         * @property parent The device.
         */
        @Serializable
        @Resource("daemon")
        class CurrentDaemon(val parent: CurrentDevice = CurrentDevice()) {
            /**
             * `POST /api/v1/devices/current/daemon/poll` (`pollDeviceDaemon`).
             *
             * @property parent The daemon.
             */
            @Serializable
            @Resource("poll")
            class DaemonPoll(val parent: CurrentDaemon = CurrentDaemon())

            /**
             * `POST /api/v1/devices/current/daemon/sessions/{id}/refuse` (`refuseDaemonOrder`).
             *
             * @property parent The daemon.
             * @property id The session.
             */
            @Serializable
            @Resource("sessions/{id}/refuse")
            class DaemonRefusal(val parent: CurrentDaemon = CurrentDaemon(), val id: String)

            /**
             * `POST /api/v1/devices/current/daemon/listings/{id}` (`answerDaemonListing`).
             *
             * @property parent The daemon.
             * @property id The listing.
             */
            @Serializable
            @Resource("listings/{id}")
            class DaemonListing(val parent: CurrentDaemon = CurrentDaemon(), val id: String)
        }

        /**
         * `PUT /api/v1/devices/current/folders` (`publishDeviceFolders`).
         *
         * @property parent The device.
         */
        @Serializable
        @Resource("folders")
        class PublishedFolders(val parent: CurrentDevice = CurrentDevice())
    }

    /**
     * `GET /api/v1/devices/{id}/folders` (`listDeviceFolders`).
     *
     * @property parent The devices.
     * @property id The device.
     */
    @Serializable
    @Resource("{id}/folders")
    class DeviceFolders(val parent: DeviceDaemonApi = DeviceDaemonApi(), val id: String) {
        /**
         * `GET /api/v1/devices/{id}/folders/{folderId}/resumable` (`listResumableSessions`).
         *
         * @property parent The device's folders.
         * @property folderId The folder.
         * @property limit The page size, 1 to 50 (20 by default).
         * @property before The `next` of the previous page.
         */
        @Serializable
        @Resource("{folderId}/resumable")
        class Resumable(
            val parent: DeviceFolders,
            val folderId: String,
            val limit: String? = null,
            val before: String? = null,
        )
    }
}

/**
 * A daemon's long poll (contract `DaemonPollRequest`).
 *
 * @property waitMillis How long to wait for an order, at most 20 s.
 * @property version The CLI's version.
 * @property features What the daemon announces.
 */
@Serializable
data class DaemonPollRequest(
    val waitMillis: Long = 0,
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/**
 * A session the daemon is asked to host (contract `DaemonOrder`).
 *
 * @property sessionId The session.
 * @property folderId The folder it starts in.
 * @property directoryName The folder's name.
 * @property agent `claude` or `codex`.
 * @property permissionMode `ask` or `bypass`.
 * @property status `creating` or `resuming`.
 * @property requestedAt When it started waiting.
 * @property resumeSessionId The device's conversation the session continues (`runners.cloud.remote.resume.v1`).
 */
@Serializable
data class DaemonOrderDto(
    val sessionId: String,
    val folderId: String,
    val directoryName: String,
    val agent: String,
    val permissionMode: String,
    val status: String,
    val requestedAt: String,
    val resumeSessionId: String? = null,
)

/**
 * A listing a member waits for (contract `DaemonListing`).
 *
 * @property requestId The listing.
 * @property folderId The folder.
 * @property limit The most conversations.
 * @property before The cursor of the page.
 */
@Serializable
data class DaemonListingDto(val requestId: String, val folderId: String, val limit: Int, val before: String? = null)

/**
 * A poll's answer (contract `DaemonPoll`).
 *
 * @property orders The sessions to host, oldest first.
 * @property onlineTtlMillis How long the device stays online without a poll.
 * @property maxWaitMillis The longest wait.
 * @property listings The listings members wait for.
 */
@Serializable
data class DaemonPollResponse(
    val orders: List<DaemonOrderDto>,
    val onlineTtlMillis: Long,
    val maxWaitMillis: Long,
    val listings: List<DaemonListingDto> = emptyList(),
)

/**
 * A folder a device's daemon offers (contract `DeviceFolder`).
 *
 * @property id The daemon's key.
 * @property name The folder's last segment.
 * @property label How its owner recognizes it.
 */
@Serializable
data class DeviceFolderDto(val id: String, val name: String, val label: String? = null)

/**
 * The folders the caller's device offers (contract `PublishDeviceFoldersRequest`).
 *
 * @property folders The folders, replacing the previous list.
 */
@Serializable
data class PublishDeviceFoldersRequest(val folders: List<DeviceFolderDto>)

/**
 * A device's daemon as the server last saw it (contract `DaemonPresence`).
 *
 * @property online It polled less than a minute ago.
 * @property lastSeenAt Its latest recorded poll.
 * @property version The CLI's version it announced.
 * @property features What it announced.
 */
@Serializable
data class DaemonPresenceDto(
    val online: Boolean,
    val lastSeenAt: String,
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/**
 * A device's daemon and published folders (contract `DeviceFolders`).
 *
 * @property deviceId The device.
 * @property daemon Its daemon, absent when it never polled or said it stopped.
 * @property folders Its folders, in order.
 */
@Serializable
data class DeviceFoldersResponse(
    val deviceId: String,
    val daemon: DaemonPresenceDto? = null,
    val folders: List<DeviceFolderDto>,
)

/**
 * Why the daemon does not host an order (contract `RefuseDaemonOrderRequest`).
 *
 * @property reason A stable reason code.
 */
@Serializable
data class RefuseDaemonOrderRequest(val reason: String)

/**
 * A conversation a session can continue (contract `ResumableSession`).
 *
 * @property id A Claude Code session id.
 * @property title A one-line title.
 * @property lastActiveAt Its last activity.
 * @property turns The messages the member sent in it.
 */
@Serializable
data class ResumableSessionDto(val id: String, val title: String, val lastActiveAt: String, val turns: Int)

/**
 * A page of a device's conversations (contract `ResumableSessionPage`).
 *
 * @property items The conversations, the most recent first.
 * @property next The cursor of the following page.
 */
@Serializable
data class ResumableSessionPageResponse(val items: List<ResumableSessionDto>, val next: String? = null)

/**
 * The daemon's answer to a listing (contract `DaemonListingAnswer`).
 *
 * @property items The page.
 * @property next The cursor of the following page.
 * @property refused Why it does not list them, instead of a page.
 */
@Serializable
data class DaemonListingAnswerRequest(
    val items: List<ResumableSessionDto> = emptyList(),
    val next: String? = null,
    val refused: String? = null,
)
