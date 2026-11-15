// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DeviceDaemons
import run.ostrom.server.kernel.auth.OwnedDevice
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.secrets.domain.SecretCipher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The daemon's side of the channel (TeamCode's `DeviceDaemonUseCase`). Every daemon call is made with the owner's CLI
 * sign-in from the device itself ([SignedInDevices.deviceOf]): a service key, a browser session or another device's
 * sign-in never reads or answers a device's queue. Accepting an order is [HostRemoteSessionUseCase]; refusing it is
 * [RefuseRemoteHostUseCase].
 */
interface DeviceDaemonUseCase {
    /**
     * @param principal The caller.
     * @return The device of its sign-in, required for every daemon call.
     * @throws RemoteSessionException `daemon_requires_device` (422).
     */
    suspend fun device(principal: AuthenticatedPrincipal): Uuid

    /**
     * Marks the daemon online, then returns its queue in the caller's organization as soon as it is not empty, or
     * empty after [wait] (at most [DeviceDaemonRules.MAX_WAIT]). The wait reads the queue every
     * [DeviceDaemonRules.POLL], holding no transaction between reads, and ends with the caller's cancellation.
     *
     * @param caller The member, in the organization the poll names.
     * @param principal Their credential.
     * @param wait How long to wait for an order.
     * @param version The CLI's version.
     * @param features What the daemon announces.
     * @return The orders and the listings.
     * @throws RemoteSessionException `invalid_version`, `invalid_features` (400), `daemon_requires_device` (422).
     */
    suspend fun poll(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        wait: Duration,
        version: String?,
        features: List<String> = emptyList(),
    ): DaemonPoll

    /**
     * The daemon stops: the device is offline at once.
     *
     * @param principal The caller.
     */
    suspend fun offline(principal: AuthenticatedPrincipal)

    /**
     * Replaces the folders the caller's device offers.
     *
     * @param principal The caller.
     * @param folders The folders.
     * @return The folders.
     * @throws RemoteSessionException `invalid_folders` (400), `daemon_requires_device` (422).
     */
    suspend fun publish(principal: AuthenticatedPrincipal, folders: List<DaemonFolder>): List<DaemonFolder>

    /**
     * The conversations of [folderId] on one of the caller's own devices that a session can continue, the most recent
     * first (`runners.cloud.remote.resume.v1`). The server holds no transcript: it asks the device's daemon and
     * waits up to [DeviceDaemonRules.LISTING_WAIT] for its answer. The request is removed when the wait ends,
     * whatever its outcome.
     *
     * @throws RemoteSessionException `device_not_found` (404), `device_offline`, `daemon_resume_unsupported` (409),
     *   `folder_not_published` or the daemon's own reason (422), `device_no_answer` (504).
     */
    suspend fun resumable(
        principal: AuthenticatedPrincipal,
        deviceId: Uuid,
        folderId: String,
        limit: Int,
        before: String?,
    ): ResumableAnswer

    /**
     * The daemon's answer to a listing of its own device.
     *
     * @throws RemoteSessionException `listing_not_found` (404) once nobody waits for it.
     */
    suspend fun answer(principal: AuthenticatedPrincipal, requestId: Uuid, answer: ResumableAnswer)

    /**
     * @return One of the caller's own devices with its daemon and folders.
     * @throws RemoteSessionException `device_not_found` (404) for another person's, an administrator's included.
     */
    suspend fun owned(principal: AuthenticatedPrincipal, deviceId: Uuid): OwnedDevice
}

/**
 * [DeviceDaemonUseCase] on the devices of sign-ins, their daemons and the sessions.
 *
 * @param devices The devices of sign-ins.
 * @param daemons The daemons' presence, folders and listings.
 * @param repository The sessions, the queue is read from.
 * @param cipher Seals the listings' answers: their titles never rest in clear text.
 * @param clock The time.
 * @param sleep How a wait waits.
 */
// TeamCode's DeviceDaemonUseCase, with the stores the identity and runners modules split.
@Suppress("LongParameterList")
class DeviceDaemonUseCaseImpl(
    private val devices: SignedInDevices,
    private val daemons: DeviceDaemons,
    private val repository: CloudSessionsRepository,
    private val cipher: SecretCipher,
    private val clock: Clock,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) : DeviceDaemonUseCase {
    override suspend fun device(principal: AuthenticatedPrincipal): Uuid {
        if (principal.kind != PrincipalKind.USER) throw daemonRequiresDevice()
        return devices.deviceOf(principal) ?: throw daemonRequiresDevice()
    }

    override suspend fun poll(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        wait: Duration,
        version: String?,
        features: List<String>,
    ): DaemonPoll {
        DeviceDaemonRules.validateVersion(version)
        DeviceDaemonRules.validateFeatures(features)
        val device = device(principal)
        daemons.touch(device, version, features, clock.now())
        // An older daemon cannot answer a listing: its device's requests are never read (and never made).
        val lists = DeviceDaemonRules.RESUME_FEATURE in features
        val until = wait.coerceIn(Duration.ZERO, DeviceDaemonRules.MAX_WAIT)
        var waited = Duration.ZERO
        while (true) {
            val orders = repository.daemonOrders(caller.orgId, device, DeviceDaemonRules.ORDER_BATCH)
                .mapNotNull(::order)
            val listings = if (lists) {
                daemons.listings(device, clock.now(), DeviceDaemonRules.LISTING_BATCH)
            } else {
                emptyList()
            }
            if (orders.isNotEmpty() || listings.isNotEmpty() || waited >= until) return DaemonPoll(orders, listings)
            sleep(DeviceDaemonRules.POLL)
            waited += DeviceDaemonRules.POLL
        }
    }

    // The queue holds only sessions started through a daemon: each has a host and a folder.
    private fun order(session: CloudSession): DaemonOrder? {
        val host = session.host
        val folder = host?.folderId ?: return null
        return DaemonOrder(
            sessionId = session.id,
            folderId = folder,
            directoryName = host.directoryName.orEmpty(),
            agent = session.agent.wire,
            permissionMode = host.permissionMode,
            status = session.state.wire,
            requestedAt = session.updatedAt,
        )
    }

    override suspend fun offline(principal: AuthenticatedPrincipal) = daemons.offline(device(principal))

    override suspend fun publish(principal: AuthenticatedPrincipal, folders: List<DaemonFolder>): List<DaemonFolder> {
        DeviceDaemonRules.validateFolders(folders)
        daemons.publish(device(principal), folders)
        return folders
    }

    override suspend fun resumable(
        principal: AuthenticatedPrincipal,
        deviceId: Uuid,
        folderId: String,
        limit: Int,
        before: String?,
    ): ResumableAnswer {
        DeviceDaemonRules.validateListing(limit, before)
        val device = owned(principal, deviceId)
        requireResume(device)
        if (device.folders.none { it.id == folderId }) throw DeviceDaemonRules.folderNotPublished(device.name)
        val now = clock.now()
        val request = daemons.askListing(device.id, folderId, limit, before, now, now + DeviceDaemonRules.LISTING_TTL)
        try {
            var waited = Duration.ZERO
            while (waited < DeviceDaemonRules.LISTING_WAIT) {
                sleep(DeviceDaemonRules.LISTING_POLL)
                waited += DeviceDaemonRules.LISTING_POLL
                val sealed = daemons.listingAnswer(request) ?: continue
                val answer = open(request, sealed)
                answer.refused?.let {
                    throw RemoteSessionException(
                        ErrorKind.UNPROCESSABLE,
                        it,
                        "${device.name} cannot read this folder's conversations ($it).",
                    )
                }
                return answer
            }
            throw RemoteSessionException(
                ErrorKind.GATEWAY_TIMEOUT,
                "device_no_answer",
                "${device.name} did not answer in time. Try again.",
            )
        } finally {
            withContext(NonCancellable) { daemons.dropListing(request) }
        }
    }

    override suspend fun answer(principal: AuthenticatedPrincipal, requestId: Uuid, answer: ResumableAnswer) {
        DeviceDaemonRules.validateAnswer(answer)
        val device = device(principal)
        val sealed = cipher.seal(json.encodeToString(StoredAnswer.of(answer)).encodeToByteArray(), context(requestId))
        if (!daemons.answerListing(device, requestId, sealed, clock.now())) throw listingNotFound()
    }

    override suspend fun owned(principal: AuthenticatedPrincipal, deviceId: Uuid): OwnedDevice {
        val userId = Uuid.parseOrNull(principal.subjectId)?.takeIf { principal.kind == PrincipalKind.USER }
            ?: throw DeviceDaemonRules.deviceNotFound()
        return daemons.owned(userId, deviceId, clock.now()) ?: throw DeviceDaemonRules.deviceNotFound()
    }

    // An envelope that no longer opens (the secrets key changed while a member waited) is an answer that never came.
    private suspend fun open(request: Uuid, sealed: String): ResumableAnswer {
        val plain = cipher.open(sealed, context(request)) ?: throw RemoteSessionException(
            ErrorKind.GATEWAY_TIMEOUT,
            "device_no_answer",
            "The device's answer could not be read. Try again.",
        )
        return json.decodeFromString<StoredAnswer>(plain.decodeToString()).domain()
    }

    /** Constants and checks of the daemon channel. */
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun context(requestId: Uuid) = "device-daemon-listing:$requestId"

        /**
         * The device's daemon is online and lists conversations ([DeviceDaemonRules.RESUME_FEATURE]).
         *
         * @param device The device.
         * @throws RemoteSessionException `device_offline` or `daemon_resume_unsupported` (409).
         */
        fun requireResume(device: OwnedDevice) {
            val daemon = device.daemon
            if (daemon?.online != true) throw DeviceDaemonRules.deviceOffline(device.name)
            if (DeviceDaemonRules.RESUME_FEATURE !in daemon.features) {
                throw RemoteSessionRules.conflict(
                    "daemon_resume_unsupported",
                    "${device.name} runs an Ostrom version that cannot continue a conversation. Update Ostrom on it " +
                        "(ostrom update).",
                )
            }
        }

        private fun listingNotFound() =
            RemoteSessionException(ErrorKind.NOT_FOUND, "listing_not_found", "Nobody waits for this listing any more.")

        private fun daemonRequiresDevice() = RemoteSessionException(
            ErrorKind.UNPROCESSABLE,
            "daemon_requires_device",
            "The daemon channel takes the CLI sign-in of the device itself (ostrom login on that device); service " +
                "keys and browser sessions cannot use it.",
        )
    }

    @Serializable
    private data class StoredSession(val id: String, val title: String, val lastActiveAt: String, val turns: Int)

    @Serializable
    private data class StoredAnswer(
        val sessions: List<StoredSession>,
        val next: String? = null,
        val refused: String? = null,
    ) {
        fun domain() = ResumableAnswer(
            sessions.map { ResumableSession(it.id, it.title, Instant.parse(it.lastActiveAt), it.turns) },
            next,
            refused,
        )

        companion object {
            fun of(answer: ResumableAnswer) = StoredAnswer(
                answer.sessions.map { StoredSession(it.id, it.title, it.lastActiveAt.toString(), it.turns) },
                answer.next,
                answer.refused,
            )
        }
    }
}
