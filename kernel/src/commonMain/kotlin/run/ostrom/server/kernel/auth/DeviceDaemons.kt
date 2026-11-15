// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A device's daemon (`runners.cloud.remote.daemon.v1`, TeamCode's `DeviceDaemonStore`): the local service of a
// signed-in CLI polls for the remote sessions its owner starts on it from elsewhere, in the folders it publishes. Its
// presence, folders and listings live with the device in the identity module; its queue is the runners module's
// sessions.

/**
 * Whether a device's daemon is connected (TeamCode's `DaemonPresence`): it polled less than [ONLINE_TTL] ago.
 *
 * @property online It polled less than [ONLINE_TTL] ago.
 * @property lastSeenAt Its latest recorded poll.
 * @property version The CLI's version it announced.
 * @property features What it announced at its latest recorded poll (`resume.v1`).
 */
data class DaemonPresence(
    val online: Boolean,
    val lastSeenAt: Instant,
    val version: String?,
    val features: List<String> = emptyList(),
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** The daemon is online while its last poll is younger than this. */
        val ONLINE_TTL: Duration = 60.seconds

        /** A poll within this delay of the previous recorded one does not write the device row again. */
        val TOUCH: Duration = 5_000.milliseconds

        /**
         * @param lastSeenAt The latest recorded poll, null when the daemon never connected or said it stopped.
         * @param version The version it announced.
         * @param now The time.
         * @param features What it announced.
         * @return Its presence, or null when it never connected or said it stopped.
         */
        fun of(lastSeenAt: Instant?, version: String?, now: Instant, features: List<String> = emptyList()) =
            lastSeenAt?.let { DaemonPresence(now - it < ONLINE_TTL, it, version, features) }
    }
}

/**
 * A folder a device's daemon offers (TeamCode's `DeviceFolder`): [id] is the daemon's own key, [name] the folder's last
 * segment, [label] how its owner recognizes it. The server never holds a path.
 *
 * @property id The daemon's key.
 * @property name The folder's last segment.
 * @property label How its owner recognizes it, shown to them only.
 */
data class DaemonFolder(val id: String, val name: String, val label: String? = null)

/**
 * One of the caller's own devices, as a start order needs it (TeamCode's `OwnedDevice`).
 *
 * @property id The device.
 * @property name Its name.
 * @property daemon Its daemon, null when it never connected or said it stopped.
 * @property folders Its published folders, in order.
 */
data class OwnedDevice(val id: Uuid, val name: String, val daemon: DaemonPresence?, val folders: List<DaemonFolder>)

/**
 * A member waiting for a folder's conversations, as the daemon's poll hands it over (TeamCode's `DaemonListing`).
 *
 * @property requestId The listing.
 * @property folderId The folder.
 * @property limit The most conversations to list.
 * @property before The cursor of the page, null for the first.
 */
data class DaemonListingRequest(val requestId: Uuid, val folderId: String, val limit: Int, val before: String?)

/**
 * The device side of the daemon channel, bound by the identity module (TeamCode's `SqlDeviceDaemonStore` but the
 * queue): presence on the device row, its published folders, and the listings members wait for. Without it, no
 * device has a daemon and the runners module serves none of its routes.
 */
// The whole of TeamCode's store but its queue: one port, as the runners module uses it all.
@Suppress("TooManyFunctions")
interface DeviceDaemons {
    /**
     * Marks the daemon seen at [now] with its version and features, unless a poll was recorded within
     * [DaemonPresence.TOUCH].
     *
     * @param deviceId The device.
     * @param version The CLI's version.
     * @param features What it announced.
     * @param now The time.
     */
    suspend fun touch(deviceId: Uuid, version: String?, features: List<String>, now: Instant)

    /**
     * Marks the daemon offline at once.
     *
     * @param deviceId The device.
     */
    suspend fun offline(deviceId: Uuid)

    /**
     * Replaces the device's folders, in their order.
     *
     * @param deviceId The device.
     * @param folders Its folders.
     */
    suspend fun publish(deviceId: Uuid, folders: List<DaemonFolder>)

    /**
     * @param userId The caller.
     * @param deviceId A device.
     * @param now The time.
     * @return The device with its presence and folders if [userId] owns it; null otherwise (whoever asks).
     */
    suspend fun owned(userId: Uuid, deviceId: Uuid, now: Instant): OwnedDevice?

    /**
     * Asks the device's daemon for a folder's conversations: a request that expires at [expiresAt], removing the
     * device's expired ones first.
     *
     * @param deviceId The device.
     * @param folderId The folder.
     * @param limit The most conversations.
     * @param before The cursor, null for the first page.
     * @param now The time.
     * @param expiresAt When the request is ignored.
     * @return Its id.
     */
    // TeamCode's askListing, with the time the store has no clock of its own for.
    @Suppress("LongParameterList")
    suspend fun askListing(
        deviceId: Uuid,
        folderId: String,
        limit: Int,
        before: String?,
        now: Instant,
        expiresAt: Instant,
    ): Uuid

    /**
     * @param deviceId The device.
     * @param now The time.
     * @param limit The most requests.
     * @return The device's requests not answered nor expired, oldest first.
     */
    suspend fun listings(deviceId: Uuid, now: Instant, limit: Int): List<DaemonListingRequest>

    /**
     * Stores the answer to a request of [deviceId] still open.
     *
     * @param deviceId The device answering.
     * @param requestId The request.
     * @param sealed The answer, encrypted by the caller.
     * @param now The time.
     * @return False when there is none (expired, answered, another device's).
     */
    suspend fun answerListing(deviceId: Uuid, requestId: Uuid, sealed: String, now: Instant): Boolean

    /**
     * @param requestId The request.
     * @return Its encrypted answer once the daemon gave it; null before.
     */
    suspend fun listingAnswer(requestId: Uuid): String?

    /**
     * Removes a request, answered or not, once its member stopped waiting.
     *
     * @param requestId The request.
     */
    suspend fun dropListing(requestId: Uuid)
}
