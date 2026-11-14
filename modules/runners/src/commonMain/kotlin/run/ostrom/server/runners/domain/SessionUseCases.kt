// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.errors.Pages
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a member asks for when creating a session.
 *
 * @property name A label, 1 to 120 characters; the repository's or the agent's name when absent.
 * @property agent The agent's wire name (`claude`, `codex`, `opencode`, `gemini`, `shell`).
 * @property repository The repository to clone, if any.
 * @property model The model the agent starts with, if chosen.
 * @property prompt The agent's first message, if any.
 * @property id The session's id, chosen by the server's caller (a run of a workflow keys its agent node's session by
 *   its visit, so a start asked again finds the first one); a new random id when absent, always for an API request.
 * @property environment The preparation recipe (`runners.cloud.environment.v1`), if any.
 */
data class CreateCloudSessionPayload(
    val name: String?,
    val agent: String,
    val repository: RepositorySpec?,
    val model: String? = null,
    val prompt: String? = null,
    val id: Uuid? = null,
    val environment: CloudEnvironmentSpec? = null,
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
     * @throws CloudHoursExhaustedException When the organization used its hours of the period.
     */
    suspend operator fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession
}

/**
 * [CreateCloudSessionUseCase] on the repository and the plan.
 *
 * @param repository The sessions.
 * @param plans The organizations' plans.
 * @param usage The hours used.
 * @param clock The time.
 */
class CreateCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
) : CreateCloudSessionUseCase {
    override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession {
        val agent = AgentKind.fromWire(payload.agent) ?: throw InvalidCloudSessionRequestException(
            "Unknown agent ${payload.agent.take(MAX_ECHO)}: use ${AgentKind.entries.joinToString { it.wire }}.",
        )
        val name = payload.name?.let { it.trim().takeIf(String::isNotEmpty) } ?: defaultName(payload) ?: agent.wire
        ensureValid(name.length <= MAX_NAME_LENGTH && name.none { it.isISOControl() }) {
            "The name must be 1 to $MAX_NAME_LENGTH characters, without control characters."
        }
        payload.repository?.let { validateRepository(it) }
        payload.environment?.let { environment ->
            ensureValid(payload.repository == null || environment.repositories.isEmpty()) {
                "Name the repositories in the environment, or repo, not both."
            }
            CloudEnvironmentRules.validate(environment)
        }
        val model = payload.model?.let { it.trim().takeIf(String::isNotEmpty) }
        ensureValid(model == null || MODEL.matches(model)) { "The model is not a valid model name." }
        val prompt = payload.prompt?.takeIf { it.isNotBlank() }
        ensureValid(prompt == null || prompt.length <= MAX_PROMPT_LENGTH) {
            "The prompt must be at most $MAX_PROMPT_LENGTH characters."
        }
        val plan = plans.planFor(caller.orgId)
        val now = clock.now()
        requireHoursLeft(usage, plan, caller.orgId, now)
        val session = CloudSession(
            id = payload.id ?: Uuid.random(),
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
            model = model,
            prompt = prompt,
            environment = payload.environment,
        )
        if (!repository.createWithinCap(session, plan.maxRunningSessions)) {
            throw CloudSessionLimitException(plan.maxRunningSessions)
        }
        return session
    }

    /** The name of a session asked without one: its repository's, the first of its recipe's, or none. */
    private fun defaultName(payload: CreateCloudSessionPayload): String? {
        val url = payload.repository?.url ?: payload.environment?.repositories?.firstOrNull()?.url ?: return null
        return CloudEnvironmentRules.defaultPath(url)
    }

    private fun validateRepository(repository: RepositorySpec) {
        ensureValid(GITHUB_REPOSITORY.matches(repository.url)) {
            "The repository must be an https://github.com/<owner>/<name> URL."
        }
        // Cloned into a directory of its name (CloudEnvironmentRules.effective): never `..`, nor a hidden one.
        CloudEnvironmentRules.validatePath(CloudEnvironmentRules.defaultPath(repository.url))
        val ref = repository.ref ?: return
        ensureValid(GIT_REF.matches(ref) && !ref.contains("..") && !ref.startsWith("-")) {
            "The repository ref is not a valid branch, tag or commit."
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_PROMPT_LENGTH = 20_000
        const val MAX_ECHO = 32
        private val MODEL = Regex("[A-Za-z0-9._:/@-]{1,100}")
        private val GITHUB_REPOSITORY = Regex(
            "https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}(\\.git)?",
        )
        private val GIT_REF = Regex("[A-Za-z0-9._/-]{1,200}")
    }
}

/**
 * Refuses when the organization used the hours of its plan this period.
 *
 * @throws CloudSessionsClosedException When cloud sessions are closed to the organization.
 * @throws CloudHoursExhaustedException When no hour is left.
 */
internal suspend fun requireHoursLeft(
    usage: CloudUsageMeter,
    plan: CloudPlan,
    orgId: Uuid,
    now: kotlin.time.Instant,
) {
    if (!plan.available) throw CloudSessionsClosedException()
    val hours = plan.monthlyHours ?: return
    if (usage.usedSeconds(orgId, now) >= hours * SECONDS_PER_HOUR) {
        throw CloudHoursExhaustedException(hours)
    }
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
 * [GetCloudSessionUseCase] on the repository, the session named with the registered runner it runs on.
 *
 * @param repository The sessions.
 * @param driver Where executions run: names the runner holding the current one.
 */
class GetCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val driver: RuntimeDriver,
) : GetCloudSessionUseCase {
    // a paused, terminated or failed session runs nowhere: no lookup
    override suspend fun invoke(caller: Caller, id: Uuid): CloudSession = repository.ownedBy(caller, id).let {
        when (it.state) {
            SessionState.PAUSED, SessionState.TERMINATED, SessionState.FAILED -> it
            else -> it.copy(runnerId = driver.holder(it))
        }
    }
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
        ensureValid(size in 1..MAX_PAGE) { "The limit must be between 1 and $MAX_PAGE." }
        // A `before` that names no session of the organization (forged, or another's) is a 400, not an empty page.
        return Pages.checked("before", before, repository.list(caller.orgId, caller.memberId, before, size)) {
            repository.get(caller.orgId, it) != null
        }
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
     * @throws CloudHoursExhaustedException When the organization used its hours of the period.
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
 * @param usage The hours used.
 * @param clock The time.
 */
class ChangeCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
) : ChangeCloudSessionUseCase {
    override suspend fun pause(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.state.isFinal) throw CloudSessionStateException("This session is over.")
        if (session.desiredState == DesiredState.PAUSED) return session
        return repository.setDesiredState(caller.orgId, id, DesiredState.PAUSED, clock.now())
            ?: throw CloudSessionNotFoundException()
    }

    override suspend fun resume(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) {
            throw CloudSessionStateException("This session is over.")
        }
        if (session.desiredState == DesiredState.RUNNING) return session
        val plan = plans.planFor(caller.orgId)
        val now = clock.now()
        requireHoursLeft(usage, plan, caller.orgId, now)
        val outcome = repository.resumeWithinCap(caller.orgId, id, plan.maxRunningSessions, now)
        if (outcome is ResumeOutcome.Resumed) return outcome.session
        throw if (outcome == ResumeOutcome.AtCap) {
            CloudSessionLimitException(plan.maxRunningSessions)
        } else {
            CloudSessionNotFoundException()
        }
    }

    override suspend fun delete(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        if (session.desiredState == DesiredState.TERMINATED || session.state.isFinal) return session
        return repository.setDesiredState(caller.orgId, id, DesiredState.TERMINATED, clock.now())
            ?: throw CloudSessionNotFoundException()
    }
}
