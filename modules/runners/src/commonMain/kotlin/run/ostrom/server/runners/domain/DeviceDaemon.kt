// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DaemonListingRequest
import run.ostrom.server.kernel.errors.ErrorKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A device's daemon (`runners.cloud.remote.daemon.v1`), as TeamCode's (`DeviceDaemon.kt`, docs/cloud-sessions.md
// "Sessions started by a device's daemon"): the local service of a signed-in CLI polls for the remote sessions its
// owner starts on it from elsewhere, in the folders it publishes.

/**
 * A remote session waiting for the device's daemon to host it (TeamCode's `DaemonOrder`): started through the daemon
 * (a folder), on its device, in phase [RemoteSessionRules.WAITING_HOST] with desired state `running`.
 *
 * @property sessionId The session.
 * @property folderId The folder it starts in.
 * @property directoryName The folder's name.
 * @property agent The agent's wire name.
 * @property permissionMode `ask` or `bypass`.
 * @property status Its state: `creating` for a new session, `resuming` for a woken one.
 * @property requestedAt When it started waiting.
 */
data class DaemonOrder(
    val sessionId: Uuid,
    val folderId: String,
    val directoryName: String,
    val agent: String,
    val permissionMode: String,
    val status: String,
    val requestedAt: Instant,
)

/**
 * A poll's answer.
 *
 * @property orders The sessions to host, oldest first.
 * @property listings The listings members wait for.
 */
data class DaemonPoll(val orders: List<DaemonOrder>, val listings: List<DaemonListingRequest> = emptyList())

/**
 * An agent conversation of a device's folder a session can continue (TeamCode's `ResumableSession`): only the device's
 * owner sees it, and the server never logs it.
 *
 * @property id A Claude Code session id.
 * @property title A one-line title.
 * @property lastActiveAt Its last activity.
 * @property turns The messages the member sent in it.
 */
data class ResumableSession(val id: String, val title: String, val lastActiveAt: Instant, val turns: Int) {
    override fun toString(): String =
        "ResumableSession(id=$id, title=[REDACTED], lastActiveAt=$lastActiveAt, turns=$turns)"
}

/**
 * The daemon's answer to a listing (TeamCode's `ResumableAnswer`).
 *
 * @property sessions A page of conversations.
 * @property next The cursor of the following page.
 * @property refused Why the daemon does not list them, instead of a page.
 */
data class ResumableAnswer(val sessions: List<ResumableSession>, val next: String? = null, val refused: String? = null)

/** The rules of the daemon channel (TeamCode's `DeviceDaemonRules`). */
// TeamCode's rules object: one check per field the channel takes, and its refusals.
@Suppress("TooManyFunctions")
object DeviceDaemonRules {
    /** The longest wait of a poll. */
    val MAX_WAIT: Duration = 20.seconds

    /** How often a waiting poll reads the queue again: one indexed read per waiting daemon per second. */
    val POLL: Duration = 1.seconds

    /** The most orders a poll answers. */
    const val ORDER_BATCH: Int = 16

    /** The most folders a device publishes. */
    const val MAX_FOLDERS: Int = 64

    /** The daemon lists a folder's conversations (`runners.cloud.remote.resume.v1`). */
    const val RESUME_FEATURE: String = "resume.v1"

    /** The most features a daemon announces. */
    const val MAX_FEATURES: Int = 8

    /** How long a member waits for the daemon's listing; the request is dropped when the wait ends. */
    val LISTING_WAIT: Duration = 10.seconds

    /** How often the waiting member's request reads its answer: one primary-key read, only while a member waits. */
    val LISTING_POLL: Duration = 250.milliseconds

    /** A request left behind (its replica stopped while it waited) is ignored after this. */
    val LISTING_TTL: Duration = 30.seconds

    /** The most listings a poll hands over. */
    const val LISTING_BATCH: Int = 4

    /** The default page of a listing. */
    const val DEFAULT_LISTING_LIMIT: Int = 20

    /** The largest page of a listing. */
    const val MAX_LISTING_LIMIT: Int = 50

    /** The longest conversation title. */
    const val TITLE_LENGTH: Int = 80

    private const val MAX_LABEL = 512
    private val conversationId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val feature = Regex("[a-z0-9._-]{1,32}")
    private val folderId = Regex("[A-Za-z0-9_-]{1,64}")
    private val version = Regex("[A-Za-z0-9._+-]{1,32}")
    private val reason = Regex("[a-z_]{1,64}")

    /**
     * @param folders The folders a daemon publishes.
     * @throws RemoteSessionException `invalid_folders` (400).
     */
    fun validateFolders(folders: List<DaemonFolder>) {
        val problem = when {
            folders.size > MAX_FOLDERS -> "Publish at most $MAX_FOLDERS folders."
            folders.map { it.id }.toSet().size != folders.size -> "Folder ids must be unique."
            folders.any { !folderId.matches(it.id) } -> "Use folder ids of 1-64 characters from A-Z a-z 0-9 _ -."
            folders.any { !RemoteSessionRules.validDirectoryName(it.name) } ->
                "Send each folder's name (its last segment, 1-255 characters), never its path."
            folders.any { folder -> folder.label?.let(::invalidLabel) == true } ->
                "Use folder labels of 1-$MAX_LABEL characters without control characters."
            else -> return
        }
        throw RemoteSessionRules.invalid("invalid_folders", problem)
    }

    private fun invalidLabel(label: String) =
        label.isBlank() || label.length > MAX_LABEL || label.any(Char::isISOControl)

    /**
     * @param value The version a daemon announces.
     * @throws RemoteSessionException `invalid_version` (400).
     */
    fun validateVersion(value: String?) {
        if (value != null && !version.matches(value)) {
            throw RemoteSessionRules.invalid(
                "invalid_version",
                "Send the daemon's version as 1-32 characters from A-Z a-z 0-9 . _ + -.",
            )
        }
    }

    /**
     * @param value A refusal's reason code.
     * @throws RemoteSessionException `invalid_reason` (400).
     */
    fun validateReason(value: String) {
        if (!reason.matches(value)) {
            throw RemoteSessionRules.invalid(
                "invalid_reason",
                "Send a stable reason code of 1-64 characters from a-z and _.",
            )
        }
    }

    /**
     * @param values What a daemon announces.
     * @throws RemoteSessionException `invalid_features` (400).
     */
    fun validateFeatures(values: List<String>) {
        if (values.size > MAX_FEATURES || values.any { !feature.matches(it) }) {
            throw RemoteSessionRules.invalid(
                "invalid_features",
                "Send at most $MAX_FEATURES daemon features of 1-32 characters from a-z 0-9 . _ -.",
            )
        }
    }

    /** @return Whether [value] is a folder id. */
    fun validFolderId(value: String): Boolean = folderId.matches(value)

    /** @return Whether [value] is a Claude Code session id (a lowercase UUID). */
    fun validConversationId(value: String): Boolean = conversationId.matches(value)

    /**
     * @param limit A listing's page size.
     * @param before Its cursor.
     * @throws RemoteSessionException `invalid_limit` or `invalid_cursor` (400).
     */
    fun validateListing(limit: Int, before: String?) {
        if (limit !in 1..MAX_LISTING_LIMIT) {
            throw RemoteSessionRules.invalid("invalid_limit", "Use a limit from 1 to $MAX_LISTING_LIMIT.")
        }
        if (before != null && Instant.parseOrNull(before) == null) {
            throw RemoteSessionRules.invalid("invalid_cursor", "Use the next of the previous page.")
        }
    }

    /**
     * What the daemon may answer: ids, short one-line titles, times and counts, nothing else and nothing larger.
     *
     * @param answer The daemon's answer.
     * @throws RemoteSessionException `invalid_listing` (400), or `invalid_reason` for its refusal.
     */
    fun validateAnswer(answer: ResumableAnswer) {
        answer.refused?.let(::validateReason)
        val problem = when {
            answer.sessions.size > MAX_LISTING_LIMIT || (answer.refused != null && answer.sessions.isNotEmpty()) ->
                "Send at most $MAX_LISTING_LIMIT conversations, or a refusal alone."
            answer.next?.let { Instant.parseOrNull(it) == null } == true -> "Send the cursor as a time."
            answer.sessions.any(::invalidConversation) ->
                "Send each conversation's id (a lowercase UUID), a one-line title of 1-$TITLE_LENGTH characters, its " +
                    "last activity and its count of messages."
            else -> return
        }
        throw RemoteSessionRules.invalid("invalid_listing", problem)
    }

    private fun invalidConversation(session: ResumableSession) =
        !validConversationId(session.id) || session.turns < 0 || session.title.isBlank() ||
            session.title.length > TITLE_LENGTH || session.title.any(Char::isISOControl)

    /** @return The refusal of a device that is not the caller's, an administrator's included. */
    fun deviceNotFound(): RemoteSessionException =
        RemoteSessionException(ErrorKind.NOT_FOUND, "device_not_found", "Device not found among your devices.")

    /** @return The refusal of a folder the device does not offer. */
    fun folderNotPublished(device: String): RemoteSessionException = RemoteSessionException(
        ErrorKind.UNPROCESSABLE,
        "folder_not_published",
        "$device does not offer this folder. Add it on the device with ostrom folders add.",
    )

    /** @return The refusal of a device whose daemon is not connected. */
    fun deviceOffline(device: String): RemoteSessionException = RemoteSessionRules.conflict(
        "device_offline",
        "$device is offline: its Ostrom daemon is not connected. Start it on the device, or choose another one.",
    )
}
