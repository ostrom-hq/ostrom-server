// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Local sessions (`runners.cloud.local.v1`), as TeamCode's (`CloudSessionService.createLocal`): the session of a
// member's own `ostrom <agent>` launch, hosted by that launch like a remote session but from any of the owner's
// devices. No pod, profile, cluster, workspace or cap; it lives as long as the launch.

/**
 * The launches of `ostrom <agent>`, as the launches module keeps them (TeamCode's `CloudSessionStore.localRun`).
 */
fun interface LocalLaunches {
    /**
     * Whether a launch is the person's own and still running: not ended (its agent token revoked) nor expired.
     *
     * @param orgId The organization the launch runs in.
     * @param userId The person, as their sign-in names them ([AuthenticatedPrincipal.subjectId]).
     * @param launchId The launch.
     * @return Whether the person may open its local session.
     */
    suspend fun running(orgId: Uuid, userId: String, launchId: String): Boolean
}

/**
 * What a member asks for when opening the local session of a launch (TeamCode's `LocalSessionDraft`).
 *
 * @property launchId The caller's own `ostrom <agent>` launch.
 * @property name A label; the agent's name when absent.
 * @property agent The agent's wire name: `claude` or `codex`.
 */
data class LocalSessionDraft(val launchId: String, val name: String?, val agent: String)

/** The rules of local sessions (TeamCode's `CloudSessionService.localSession` and the run checks of `createLocal`). */
object LocalSessionRules {
    /** Why a local session whose launch never took its execution ended. */
    const val RUN_LOST: String = "run_lost"

    /** A launch id, as the launches module makes them (`lch_` and 32 hex characters). */
    private val LAUNCH_ID = Regex("lch_[0-9a-f]{32}")

    /**
     * @param id A launch id as sent.
     * @return Whether it is one.
     */
    fun validLaunchId(id: String): Boolean = LAUNCH_ID.matches(id)

    /** @return The 409 of a person's pause, resume or message on a local session. */
    fun localSession(): RemoteSessionException = RemoteSessionRules.conflict(
        "local_session",
        "A local session lives as long as its ostrom launch: it cannot be paused, resumed or sent a message. End " +
            "the launch, or delete the session.",
    )

    /** @return The 404 of a launch that is unknown, ended or another person's. */
    fun launchNotFound(): OstromException =
        RemoteSessionException(ErrorKind.NOT_FOUND, "launch_not_found", "This launch is unknown, ended or not yours.")
}

/** Opens the local session of the caller's own `ostrom <agent>` launch. */
interface CreateLocalSessionUseCase {
    /**
     * Opens it (TeamCode's `createLocal`): only for the caller's own launch, still running, and once per launch: a
     * second call returns the first session, whatever its state, independent of any `Idempotency-Key`. It counts
     * toward no cap.
     *
     * @param caller The member, who becomes the owner.
     * @param principal Their credential, which must be the person who started the launch.
     * @param draft The request.
     * @return The session, `creating` in phase [RemoteSessionRules.WAITING_HOST], and whether it replays an earlier
     *   one.
     * @throws OnBehalfForbiddenException When [caller] acts on behalf of another member (`Ostrom-On-Behalf-Of`).
     * @throws RemoteSessionException `invalid_launch` or `invalid_agent` (400), `launch_not_found` (404).
     */
    suspend operator fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        draft: LocalSessionDraft,
    ): KeyedCreation
}

/**
 * [CreateLocalSessionUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param launches The launches; null refuses every local session (`launch_not_found`), as where the launches module
 *   is not loaded.
 * @param clock The time.
 */
class CreateLocalSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val launches: LocalLaunches?,
    private val clock: Clock,
) : CreateLocalSessionUseCase {
    // TeamCode's createLocal: one refusal for each check, in its order.
    @Suppress("ThrowsCount")
    override suspend fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        draft: LocalSessionDraft,
    ): KeyedCreation {
        // The launch is the caller's own: nobody opens one for another member.
        if (caller.onBehalf) throw OnBehalfForbiddenException()
        if (!LocalSessionRules.validLaunchId(draft.launchId)) {
            throw RemoteSessionRules.invalid("invalid_launch", "Send the launchId of your ostrom launch.")
        }
        val name = draft.name?.trim()?.takeIf(String::isNotEmpty)
        ensureValid(name == null || (name.length <= MAX_NAME_LENGTH && name.none { it.isISOControl() })) {
            "The name must be 1 to $MAX_NAME_LENGTH characters, without control characters."
        }
        val agent = AgentKind.fromWire(draft.agent)?.takeIf { it in RemoteSessionRules.REMOTE_AGENTS }
            ?: throw RemoteSessionRules.invalid("invalid_agent", "Use the agent claude or codex.")
        // A person's own launch: a service key or an agent token started none.
        val person = principal.kind == PrincipalKind.USER
        val running = person && launches?.running(caller.orgId, principal.subjectId, draft.launchId) == true
        if (!running) throw LocalSessionRules.launchNotFound()
        val session = findOwn(caller, draft.launchId)
            ?: return store(caller, newSession(caller, draft.launchId, name ?: agent.wire, agent), draft.launchId)
        return KeyedCreation(session, replayed = true)
    }

    /** Stores [session]: no cap holds a local session, and a launch has one only. */
    private suspend fun store(caller: Caller, session: CloudSession, launchId: String): KeyedCreation = try {
        repository.createWithinCap(session, Int.MAX_VALUE)
        KeyedCreation(session, replayed = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Two calls for one launch at once: the second one finds the first one's session (unique per launch).
        val first = findOwn(caller, launchId) ?: throw error
        KeyedCreation(first, replayed = true)
    }

    private fun newSession(caller: Caller, launchId: String, name: String, agent: AgentKind): CloudSession {
        val now = clock.now()
        return CloudSession(
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
            // No idle pause and no run bound: the launch is the session.
            limits = SessionLimits(idleTimeoutMinutes = 0, maxRunMinutes = 0, restrictedEgress = false),
            createdAt = now,
            updatedAt = now,
            lastActivityAt = now,
            mode = SessionMode.CONVERSATION,
            kind = SessionKind.LOCAL,
            createdBy = caller.attribution,
            launchId = launchId,
        )
    }

    private suspend fun findOwn(caller: Caller, launchId: String): CloudSession? =
        repository.findLocal(caller.orgId, launchId)?.takeIf { it.ownerId == caller.memberId }

    private companion object {
        const val MAX_NAME_LENGTH = 120
    }
}
