package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a member asks for when creating a session.
 *
 * @property name A label, 1 to 120 characters.
 * @property agent The agent's wire name (`claude`, `codex`, `opencode`, `gemini`, `shell`).
 * @property repository The repository to clone, if any.
 */
data class CreateCloudSessionPayload(
    val name: String,
    val agent: String,
    val repository: RepositorySpec?,
)

/** Creates a cloud session for the caller, within their organization's plan. */
interface CreateCloudSessionUseCase {
    /**
     * Creates the session; the reconciler starts it.
     *
     * @param caller The member, who becomes the owner.
     * @param payload The request.
     *
     * @return The session, `creating`.
     *
     * @throws InvalidCloudSessionRequestException When the request is malformed.
     * @throws CloudSessionLimitException When the organization runs as many sessions as its plan allows.
     * @throws CloudHoursExhaustedException When the organization used its hours of the month.
     */
    suspend operator fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession
}

/**
 * [CreateCloudSessionUseCase] on the repository and the plan.
 *
 * @param repository The sessions.
 * @param plans The organizations' plans.
 * @param clock The time.
 */
class CreateCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val clock: Clock,
) : CreateCloudSessionUseCase {
    override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession {
        val name = payload.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() }) {
            throw InvalidCloudSessionRequestException("The name must be 1 to $MAX_NAME_LENGTH characters, without control characters.")
        }
        val agent = AgentKind.fromWire(payload.agent)
            ?: throw InvalidCloudSessionRequestException("Unknown agent ${payload.agent.take(32)}: use ${AgentKind.entries.joinToString { it.wire }}.")
        payload.repository?.let { validateRepository(it) }
        val plan = plans.planFor(caller.orgId)
        val now = clock.now()
        requireHoursLeft(repository, plan, caller.orgId, now)
        val session = CloudSession(
            id = Uuid.random(),
            orgId = caller.orgId,
            ownerId = caller.memberId,
            name = name,
            agent = agent,
            repository = payload.repository,
            state = SessionState.CREATING,
            desiredState = DesiredState.RUNNING,
            phase = "scheduling",
            epoch = 0,
            generation = 0,
            failure = null,
            limits = plan.sessionLimits(),
            createdAt = now,
            updatedAt = now,
        )
        if (!repository.createWithinCap(session, plan.maxRunningSessions)) {
            throw CloudSessionLimitException(plan.maxRunningSessions)
        }
        return session
    }

    private fun validateRepository(repository: RepositorySpec) {
        if (!GITHUB_REPOSITORY.matches(repository.url)) {
            throw InvalidCloudSessionRequestException("The repository must be an https://github.com/<owner>/<name> URL.")
        }
        val ref = repository.ref ?: return
        if (!GIT_REF.matches(ref) || ref.contains("..") || ref.startsWith("-")) {
            throw InvalidCloudSessionRequestException("The repository ref is not a valid branch, tag or commit.")
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 120
        val GITHUB_REPOSITORY = Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}(\\.git)?")
        val GIT_REF = Regex("[A-Za-z0-9._/-]{1,200}")
    }
}

/**
 * Refuses when the organization used the hours of its plan this month.
 *
 * @throws CloudHoursExhaustedException When no hour is left.
 */
internal suspend fun requireHoursLeft(repository: CloudSessionsRepository, plan: CloudPlan, orgId: Uuid, now: kotlin.time.Instant) {
    val hours = plan.monthlyHours ?: return
    if (repository.usageSeconds(orgId, monthOf(now)) >= hours * SECONDS_PER_HOUR) throw CloudHoursExhaustedException(hours)
}

internal const val SECONDS_PER_HOUR: Long = 3600

/** Reads one of the caller's sessions. */
interface GetCloudSessionUseCase {
    /**
     * Reads a session.
     *
     * @param caller The member.
     * @param id The session.
     *
     * @return The session.
     *
     * @throws CloudSessionNotFoundException When it does not exist or is not the caller's.
     */
    suspend operator fun invoke(caller: Caller, id: Uuid): CloudSession
}

/**
 * [GetCloudSessionUseCase] on the repository.
 *
 * @param repository The sessions.
 */
class GetCloudSessionUseCaseImpl(private val repository: CloudSessionsRepository) : GetCloudSessionUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid): CloudSession = repository.ownedBy(caller, id)
}

/**
 * Reads a session the caller owns.
 *
 * @throws CloudSessionNotFoundException Otherwise, exactly as for an unknown id.
 */
internal suspend fun CloudSessionsRepository.ownedBy(caller: Caller, id: Uuid): CloudSession =
    get(caller.orgId, id)?.takeIf { it.ownerId == caller.memberId } ?: throw CloudSessionNotFoundException()

/** Lists the caller's sessions. */
interface ListCloudSessionsUseCase {
    /**
     * Lists a page.
     *
     * @param caller The member.
     * @param before The cursor of the previous page (its last session's id), or null for the first page.
     * @param limit The page size, 1 to 50; 20 when null.
     *
     * @return The sessions, newest first.
     *
     * @throws InvalidCloudSessionRequestException When the limit is out of bounds.
     */
    suspend operator fun invoke(caller: Caller, before: Uuid?, limit: Int?): List<CloudSession>
}

/**
 * [ListCloudSessionsUseCase] on the repository.
 *
 * @param repository The sessions.
 */
class ListCloudSessionsUseCaseImpl(private val repository: CloudSessionsRepository) : ListCloudSessionsUseCase {
    override suspend fun invoke(caller: Caller, before: Uuid?, limit: Int?): List<CloudSession> {
        val size = limit ?: DEFAULT_PAGE
        if (size !in 1..MAX_PAGE) throw InvalidCloudSessionRequestException("The limit must be between 1 and $MAX_PAGE.")
        return repository.list(caller.orgId, caller.memberId, before, size)
    }

    private companion object {
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 50
    }
}

/** Changes the desired state of one of the caller's sessions: pause, resume or delete. */
interface ChangeCloudSessionUseCase {
    /**
     * Asks for a pause: the agent is stopped, the workspace kept. Pausing a paused session changes nothing.
     *
     * @param caller The member.
     * @param id The session.
     *
     * @return The session after the request.
     *
     * @throws CloudSessionNotFoundException When it does not exist or is not the caller's.
     * @throws CloudSessionStateException When the session is over.
     */
    suspend fun pause(caller: Caller, id: Uuid): CloudSession

    /**
     * Asks for a resume, within the plan: a new execution starts on the kept workspace.
     *
     * @param caller The member.
     * @param id The session.
     *
     * @return The session after the request.
     *
     * @throws CloudSessionNotFoundException When it does not exist or is not the caller's.
     * @throws CloudSessionStateException When the session is over.
     * @throws CloudSessionLimitException When the organization runs as many sessions as its plan allows.
     * @throws CloudHoursExhaustedException When the organization used its hours of the month.
     */
    suspend fun resume(caller: Caller, id: Uuid): CloudSession

    /**
     * Asks for the deletion: the execution stops and the workspace is deleted. Deleting twice changes nothing.
     *
     * @param caller The member.
     * @param id The session.
     *
     * @return The session after the request.
     *
     * @throws CloudSessionNotFoundException When it does not exist or is not the caller's.
     */
    suspend fun delete(caller: Caller, id: Uuid): CloudSession
}

/**
 * [ChangeCloudSessionUseCase] on the repository and the plan.
 *
 * @param repository The sessions.
 * @param plans The organizations' plans.
 * @param clock The time.
 */
class ChangeCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val clock: Clock,
) : ChangeCloudSessionUseCase {
    override suspend fun pause(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.state.isFinal) throw CloudSessionStateException("This session is over.")
        if (session.desiredState == DesiredState.PAUSED) return session
        return repository.setDesiredState(caller.orgId, id, DesiredState.PAUSED, clock.now()) ?: throw CloudSessionNotFoundException()
    }

    override suspend fun resume(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) {
            throw CloudSessionStateException("This session is over.")
        }
        if (session.desiredState == DesiredState.RUNNING) return session
        val plan = plans.planFor(caller.orgId)
        val now = clock.now()
        requireHoursLeft(repository, plan, caller.orgId, now)
        return when (val outcome = repository.resumeWithinCap(caller.orgId, id, plan.maxRunningSessions, now)) {
            is ResumeOutcome.Resumed -> outcome.session
            ResumeOutcome.AtCap -> throw CloudSessionLimitException(plan.maxRunningSessions)
            ResumeOutcome.NotFound -> throw CloudSessionNotFoundException()
        }
    }

    override suspend fun delete(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.desiredState == DesiredState.TERMINATED || session.state.isFinal) return session
        return repository.setDesiredState(caller.orgId, id, DesiredState.TERMINATED, clock.now()) ?: throw CloudSessionNotFoundException()
    }
}
