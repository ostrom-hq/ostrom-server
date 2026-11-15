// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DeviceRemovalListener
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

// Remote sessions (`runners.cloud.remote.v1`), as TeamCode's (`CloudSessionService.createRemote`, `host`): a cloud
// session whose runner is its owner's own device, under the owner's own sign-in instead of a pod's token, hosted by
// `ostrom remote`. No profile, cluster, workspace, plan cap or hours: the server only keeps its lifecycle.

/**
 * Where a session runs.
 *
 * @property wire The stable name in the API and the database.
 */
enum class SessionKind(val wire: String) {
    /** A pod (or container) the server starts: every session before remote ones, and the default. */
    CLOUD("cloud"),

    /** Its owner's own device runs it, always in [SessionMode.CONVERSATION]. */
    REMOTE("remote"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param wire A stored or requested name.
         * @return The kind, or null when the name is unknown.
         */
        fun fromWire(wire: String): SessionKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The device a remote session runs on (TeamCode's `CloudSessionHost`).
 *
 * @property deviceId The device of its owner's sign-in at creation; null once the device was removed, and the session
 *   can never run again.
 * @property directoryName The last segment of the directory the agent works in, never its path.
 * @property permissionMode `ask` or `bypass` ([RemoteSessionRules.PERMISSION_MODES]).
 * @property folderId The published folder a device's daemon started it in; null for one started on the device itself.
 */
data class RemoteHost(
    val deviceId: Uuid?,
    val directoryName: String?,
    val permissionMode: String,
    val folderId: String? = null,
)

/**
 * What a member asks for when creating a remote session (TeamCode's `RemoteSessionDraft`).
 *
 * @property name A label; the directory's name when absent.
 * @property agent The agent's wire name: `claude` or `codex`.
 * @property model The model the agent starts with, if chosen.
 * @property effort The agent's effort, if chosen.
 * @property prompt The agent's first message, if any.
 * @property directoryName The directory's last segment.
 * @property permissionMode `ask` (null) or `bypass`.
 * @property deviceId Another device of the caller's, whose daemon starts the session
 *   (`runners.cloud.remote.daemon.v1`).
 * @property folderId With [deviceId], one of its published folders.
 * @property resumeSessionId With [deviceId] and [folderId], the device's conversation it continues.
 */
data class RemoteSessionDraft(
    val name: String?,
    val agent: String,
    val model: String? = null,
    val effort: String? = null,
    val prompt: String? = null,
    val directoryName: String? = null,
    val permissionMode: String? = null,
    val deviceId: String? = null,
    val folderId: String? = null,
    val resumeSessionId: String? = null,
)

/**
 * A refusal of a remote session, with its contract key.
 *
 * @param kind The HTTP status it maps to.
 * @param type The stable key.
 * @param message What to do.
 */
class RemoteSessionException(kind: ErrorKind, type: String, message: String) : OstromException(kind, message, type)

/** The idle timeout of an organization's remote sessions (its cloud setting `remoteIdleTimeoutMinutes`). */
fun interface RemoteIdleTimeout {
    /**
     * @param orgId The organization.
     * @return Minutes without activity before a remote session pauses; 0 never pauses one.
     */
    suspend fun minutes(orgId: Uuid): Int
}

/**
 * The rules of remote sessions (TeamCode's `CloudSessionService` companion: `WAITING_HOST`, `validateRemote`,
 * `remoteRequiresDevice`, and its `deviceOf` and `requireHost`).
 */
object RemoteSessionRules {
    /** The phase of a remote session waiting for its host to start an execution. */
    const val WAITING_HOST: String = "waiting_host"

    /** The phase of a remote session whose host took a new epoch and has not reported ready yet. */
    const val STARTING_HOST: String = "starting_host"

    /** Why the reconciler paused a remote session whose host went silent. */
    const val HOST_OFFLINE: String = "host_offline"

    /** Why a remote session ended with its device. */
    const val DEVICE_REMOVED: String = "device_removed"

    /** Claude Code's requests come to the clients; Codex stays in its sandbox. The default. */
    const val ASK: String = "ask"

    /** Every tool use is approved in advance ([CloudSession.permissionBypass] from creation). */
    const val BYPASS: String = "bypass"

    /** Every permission mode. */
    val PERMISSION_MODES: Set<String> = setOf(ASK, BYPASS)

    /** The agents a device hosts. */
    val REMOTE_AGENTS: Set<AgentKind> = setOf(AgentKind.CLAUDE, AgentKind.CODEX)

    /** The longest directory name. */
    const val MAX_DIRECTORY_NAME: Int = 255

    /** The default of `remoteIdleTimeoutMinutes`. */
    const val DEFAULT_IDLE_TIMEOUT_MINUTES: Int = 60

    /** The longest `remoteIdleTimeoutMinutes`. */
    const val MAX_IDLE_TIMEOUT_MINUTES: Int = 1440

    /**
     * Checks a draft before anything is read (TeamCode's `validateRemote`).
     *
     * @param draft The draft.
     * @return Its agent.
     * @throws RemoteSessionException `invalid_agent`, `invalid_folder`, `invalid_resume`, `invalid_directory_name` or
     *   `invalid_permission_mode` (400).
     */
    fun validateRemote(draft: RemoteSessionDraft): AgentKind {
        val agent = AgentKind.fromWire(draft.agent)?.takeIf { it in REMOTE_AGENTS }
        val directory = draft.directoryName
        val problem = when {
            agent == null -> "invalid_agent" to "Use the agent claude or codex."
            draft.deviceId == null && draft.folderId != null ->
                "invalid_folder" to "A folderId needs the deviceId whose daemon published it."
            draft.resumeSessionId != null && draft.deviceId == null ->
                "invalid_resume" to
                    "A resumeSessionId needs the deviceId and folderId of the conversation it continues."
            // A daemon's folder names its directory; otherwise the draft names it, a last segment only.
            directory?.let { !validDirectoryName(it) } ?: (draft.deviceId == null) -> "invalid_directory_name" to
                "Send the directory's name (its last segment, 1-$MAX_DIRECTORY_NAME characters), never its path."
            draft.permissionMode != null && draft.permissionMode !in PERMISSION_MODES ->
                "invalid_permission_mode" to "Use the permission mode ask or bypass."
            else -> return agent
        }
        throw invalid(problem.first, problem.second)
    }

    /**
     * @param name A directory name as sent.
     * @return Whether it is a last segment: not `.` or `..`, no separator nor control character.
     */
    fun validDirectoryName(name: String): Boolean =
        name.length in 1..MAX_DIRECTORY_NAME && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    /**
     * The caller's device, required for anything that runs a remote session (TeamCode's `deviceOf`).
     *
     * @param devices The devices of sign-ins, null where the identity module binds none.
     * @param principal The caller.
     * @return The device of its sign-in.
     * @throws RemoteSessionException `remote_requires_device` (422) for a service key or a credential of no device.
     */
    suspend fun deviceOf(devices: SignedInDevices?, principal: AuthenticatedPrincipal): Uuid {
        if (principal.kind != PrincipalKind.USER || devices == null) throw remoteRequiresDevice()
        return devices.deviceOf(principal) ?: throw remoteRequiresDevice()
    }

    /**
     * A remote session runs, and resumes, only on its own device (TeamCode's `requireHost`).
     *
     * @param device The caller's device.
     * @param session The session.
     * @throws RemoteSessionException `remote_host_removed` or `remote_host_mismatch` (409).
     */
    fun requireHost(device: Uuid, session: CloudSession) {
        val host = session.host?.deviceId ?: throw conflict(
            "remote_host_removed",
            "The device of this remote session was removed; the session cannot run again.",
        )
        if (host != device) {
            throw conflict("remote_host_mismatch", "This remote session runs on another device; resume it there.")
        }
    }

    /** @return A 400 with [type]. */
    fun invalid(type: String, message: String): RemoteSessionException =
        RemoteSessionException(ErrorKind.INVALID_REQUEST, type, message)

    /** @return A 409 with [type]. */
    fun conflict(type: String, message: String): RemoteSessionException =
        RemoteSessionException(ErrorKind.CONFLICT, type, message)

    private fun remoteRequiresDevice() = RemoteSessionException(
        ErrorKind.UNPROCESSABLE,
        "remote_requires_device",
        "A remote session runs on the device of an interactive login. Sign in with ostrom login on this device; " +
            "service keys cannot host one.",
    )
}

/** Creates a remote session on the caller's own device. */
interface CreateRemoteSessionUseCase {
    /**
     * Creates it, waiting for its host (TeamCode's `createRemote`): the device is the one of the caller's sign-in, so a
     * service key or a sign-in without an installation is refused, and nobody creates one for another member. It
     * counts toward neither the plan's running cap nor its hours, and a device has no cap.
     *
     * @param caller The member, who becomes the owner.
     * @param principal Their credential, whose device hosts it.
     * @param draft The request.
     * @param key The request's `Idempotency-Key` and the digest of its canonical request, if any.
     * @return The session, `creating` in phase [RemoteSessionRules.WAITING_HOST], and whether it replays an earlier
     *   one.
     * @throws RemoteSessionException `remote_requires_device` (422), `device_target_unsupported` (400) and the
     *   refusals of [RemoteSessionRules.validateRemote].
     */
    suspend operator fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        draft: RemoteSessionDraft,
        key: Pair<String, String>? = null,
    ): KeyedCreation
}

/**
 * [CreateRemoteSessionUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param devices The devices of sign-ins; null refuses every remote session.
 * @param idleTimeout The organization's idle timeout of remote sessions.
 * @param clock The time.
 */
class CreateRemoteSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val devices: SignedInDevices?,
    private val idleTimeout: RemoteIdleTimeout,
    private val clock: Clock,
) : CreateRemoteSessionUseCase {
    override suspend fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        draft: RemoteSessionDraft,
        key: Pair<String, String>?,
    ): KeyedCreation {
        val agent = RemoteSessionRules.validateRemote(draft)
        // The daemons of members' devices are not served yet (`runners.cloud.remote.daemon.v1`).
        if (draft.deviceId != null) {
            throw RemoteSessionRules.invalid(
                "device_target_unsupported",
                "This server does not start remote sessions on another device.",
            )
        }
        // A service key acting for its creator is no device: nobody hosts a session for another member.
        val device = RemoteSessionRules.deviceOf(devices, principal)
        // Without a device target, the draft names its directory (validateRemote): the name when none is given.
        val name = listOfNotNull(draft.name?.let { it.trim().takeIf(String::isNotEmpty) }, draft.directoryName).first()
        ensureValid(name.length <= MAX_NAME_LENGTH && name.none { it.isISOControl() }) {
            "The name must be 1 to $MAX_NAME_LENGTH characters, without control characters."
        }
        val model = draft.model?.let { it.trim().takeIf(String::isNotEmpty) }
        ensureValid(model == null || MODEL.matches(model)) { "The model is not a valid model name." }
        draft.effort?.let(CloudConversationRules::validateEffort)
        val prompt = draft.prompt?.takeIf { it.isNotBlank() }
        ensureValid(prompt == null || prompt.length <= MAX_PROMPT_LENGTH) {
            "The prompt must be at most $MAX_PROMPT_LENGTH characters."
        }
        val now = clock.now()
        val mode = draft.permissionMode ?: RemoteSessionRules.ASK
        val session = CloudSession(
            id = Uuid.random(),
            orgId = caller.orgId,
            ownerId = caller.memberId,
            name = name,
            agent = agent,
            repository = null,
            state = SessionState.CREATING,
            desiredState = DesiredState.RUNNING,
            phase = RemoteSessionRules.WAITING_HOST,
            epoch = 0,
            generation = 0,
            failure = null,
            limits = SessionLimits(idleTimeout.minutes(caller.orgId), maxRunMinutes = 0, restrictedEgress = false),
            createdAt = now,
            updatedAt = now,
            lastActivityAt = now,
            model = model,
            prompt = prompt,
            mode = SessionMode.CONVERSATION,
            effort = draft.effort,
            permissionBypass = PermissionBypass(caller.memberId, principal.credentialId, now)
                .takeIf { mode == RemoteSessionRules.BYPASS },
            kind = SessionKind.REMOTE,
            host = RemoteHost(device, draft.directoryName, mode),
        )
        return store(session, key)
    }

    /** Stores [session], once per [key]: no cap holds a remote session. */
    private suspend fun store(session: CloudSession, key: Pair<String, String>?): KeyedCreation {
        if (key == null) {
            repository.createWithinCap(session, Int.MAX_VALUE)
            return KeyedCreation(session, replayed = false)
        }
        val record = CreateKey(key.first, key.second, session.createdAt + IDEMPOTENCY_RETENTION)
        return when (val outcome = repository.createKeyed(session, Int.MAX_VALUE, record)) {
            is KeyedCreateOutcome.Replayed -> KeyedCreation(outcome.session, replayed = true)
            KeyedCreateOutcome.Conflict -> throw CloudSessionIdempotencyConflictException(
                "This Idempotency-Key was already used with a different request, or its session no longer exists.",
            )
            else -> KeyedCreation(session, replayed = false)
        }
    }

    private companion object {
        private val IDEMPOTENCY_RETENTION = 24.hours
        const val MAX_NAME_LENGTH = 120
        const val MAX_PROMPT_LENGTH = 20_000
        private val MODEL = Regex("[A-Za-z0-9._:/@-]{1,100}")
    }
}

/**
 * The execution a remote session's host just started (TeamCode's `CloudHostView`).
 *
 * @property orgId The session's organization, which the host names on the runner channel.
 * @property epoch The new execution.
 * @property session The session, at that execution.
 */
data class CloudHost(val orgId: Uuid, val epoch: Long, val session: CloudSession)

/** Starts the next execution of a remote session on its host (`POST /api/v1/cloud/sessions/{id}/host`). */
interface HostRemoteSessionUseCase {
    /**
     * Starts it (TeamCode's `host`): only the owner, from the session's device. A new epoch fences the execution it
     * replaces (a host started twice: the first one gets `409 execution_fenced` and exits). A paused session resumes;
     * a creating one keeps creating.
     *
     * @param caller The member.
     * @param principal Their credential.
     * @param id The session.
     * @return The new execution.
     * @throws CloudSessionNotFoundException When the caller owns no such session.
     * @throws RemoteSessionException `not_remote`, `remote_host_removed`, `remote_host_mismatch`,
     *   `session_not_running`, `session_pausing`, `session_busy` (409) or `remote_requires_device` (422).
     */
    suspend operator fun invoke(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudHost
}

/**
 * [HostRemoteSessionUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param devices The devices of sign-ins; null refuses every host.
 * @param clock The time.
 */
class HostRemoteSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val devices: SignedInDevices?,
    private val clock: Clock,
) : HostRemoteSessionUseCase {
    override suspend fun invoke(caller: Caller, principal: AuthenticatedPrincipal, id: Uuid): CloudHost {
        val found = repository.ownedBy(caller, id)
        if (found.kind != SessionKind.REMOTE) {
            throw RemoteSessionRules.conflict(
                "not_remote",
                "This is a cloud session; it runs in a pod the server starts.",
            )
        }
        RemoteSessionRules.requireHost(RemoteSessionRules.deviceOf(devices, principal), found)
        repeat(MAX_ATTEMPTS) {
            val current = repository.ownedBy(caller, id)
            if (current.state.isFinal || current.desiredState == DesiredState.TERMINATED) {
                throw RemoteSessionRules.conflict(
                    "session_not_running",
                    "The cloud session is terminated or being deleted.",
                )
            }
            if (current.state == SessionState.PAUSING) {
                throw RemoteSessionRules.conflict(
                    "session_pausing",
                    "The session is still pausing on its host. Try again once it is paused.",
                )
            }
            val now = clock.now()
            val state = if (current.state == SessionState.CREATING) SessionState.CREATING else SessionState.RESUMING
            SessionStateMachine.requireTransition(current.state, state)
            // A remote session's failure only ever says why its last execution ended (its host went offline).
            val next = current.copy(
                state = state,
                phase = RemoteSessionRules.STARTING_HOST,
                desiredState = DesiredState.RUNNING,
                epoch = current.epoch + 1,
                failure = null,
                executionStartedAt = now,
                readyAt = null,
                lastHeartbeatAt = null,
                runnerStoppedAt = null,
                pausingSince = null,
                pausedAt = null,
                expiresAt = null,
                backgroundSince = null,
                backgroundSeenAt = null,
                runnerTokenHash = null,
                updatedAt = now,
            )
            // The reconciler watches the new execution from now on (its first heartbeat, then the next ones).
            repository.compareAndSet(next, now)?.let { return CloudHost(it.orgId, it.epoch, it) }
        }
        throw RemoteSessionRules.conflict("session_busy", "The cloud session changed concurrently. Retry the request.")
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Ends the remote sessions of a removed device (TeamCode's `SqlDeviceService.revokeDevice`): they could only ever run
 * there. Each is asked to terminate with [RemoteSessionRules.DEVICE_REMOVED], the reconciler finishes it, transcripts
 * kept, and every session it hosted loses its host.
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class EndRemovedDeviceSessions(
    private val repository: CloudSessionsRepository,
    private val clock: Clock,
) : DeviceRemovalListener {
    override suspend fun removed(userId: Uuid, deviceId: Uuid) {
        val now = clock.now()
        repository.hostedOn(deviceId).forEach { repository.endOnRemovedDevice(it, deviceId, now) }
    }
}
