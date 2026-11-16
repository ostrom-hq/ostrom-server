// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.errors.Pages
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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
 * @property profileId The cloud profile a Kubernetes session runs on; the one the owner may use when absent.
 * @property image The session image instead of the profile's, one of its allowed overrides.
 * @property mode `terminal` (null) or `conversation` (claude and codex only).
 * @property effort The agent's effort (reasoning level), if chosen.
 * @property automationId The automation whose run starts it (TeamCode's `CloudSessionDraft.automationId`); never from
 *   an API request.
 */
data class CreateCloudSessionPayload(
    val name: String?,
    val agent: String,
    val repository: RepositorySpec?,
    val model: String? = null,
    val prompt: String? = null,
    val id: Uuid? = null,
    val environment: CloudEnvironmentSpec? = null,
    val profileId: Uuid? = null,
    val image: String? = null,
    val mode: String? = null,
    val effort: String? = null,
    val automationId: Uuid? = null,
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
     * @throws CloudSessionRetainedLimitException When the member already keeps as many cloud sessions as allowed.
     * @throws CloudHoursExhaustedException When the organization used its hours of the period.
     * @throws SessionProfileNotFoundException When a Kubernetes session names no profile of the organization; and
     *   the other refusals of [SessionProfiles.choose].
     */
    suspend operator fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession

    /**
     * Creates the session under an `Idempotency-Key` (`runners.cloud.idempotency.v1`, TeamCode's create): the same
     * key from the same member with the same request, within 24 hours, answers the session it created, as it is now,
     * and creates nothing. Concurrent requests with one key create one session; the others replay it.
     *
     * @param caller The member, who becomes the owner and in whose name the key is kept.
     * @param payload The request.
     * @param key The key, valid ([run.ostrom.core.api.Idempotency.isValid]).
     * @param requestDigest The SHA-256 (hex) of the canonical request.
     *
     * @return The session and whether it replays an earlier creation.
     *
     * @throws CloudSessionIdempotencyConflictException When the key was used with another request, or its session no
     *   longer exists.
     * @throws InvalidCloudSessionRequestException And the other refusals of [invoke].
     */
    suspend fun createKeyed(
        caller: Caller,
        payload: CreateCloudSessionPayload,
        key: String,
        requestDigest: String,
    ): KeyedCreation
}

/**
 * What [CreateCloudSessionUseCase.createKeyed] answers.
 *
 * @property session The session.
 * @property replayed True when the key had already created it: nothing was created.
 */
data class KeyedCreation(val session: CloudSession, val replayed: Boolean)

/**
 * [CreateCloudSessionUseCase] on the repository and the plan. An API creation is journaled `cloud.session.created`, in
 * the creation's transaction.
 *
 * @param repository The sessions.
 * @param plans The organizations' plans.
 * @param usage The hours used.
 * @param clock The time.
 * @param profiles The profile a Kubernetes session runs on; none elsewhere.
 * @param caps The per-member caps: a creation is refused at the owner's retained one (409 `cloud_session_limit`,
 *   TeamCode's). An automation's session is bounded by its automation instead.
 */
@Suppress("LongParameterList") // The ports a creation reads, each named at its one call site.
class CreateCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val clock: Clock,
    private val profiles: SessionProfiles = NoSessionProfiles,
    private val caps: MemberSessionCaps = NoMemberSessionCaps,
) : CreateCloudSessionUseCase {
    override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession =
        existing(caller, payload) ?: created(caller, payload)

    override suspend fun createKeyed(
        caller: Caller,
        payload: CreateCloudSessionPayload,
        key: String,
        requestDigest: String,
    ): KeyedCreation {
        val (session, plan) = drafted(caller, payload)
        // Scoped to the member who sent it, whoever the session is for (TeamCode's idempotency scope).
        val record = CreateKey(key, requestDigest, session.createdAt + IDEMPOTENCY_RETENTION, caller.initiatorId)
        val journal = caller.journal(session, CREATED)
        val outcome = repository.createKeyed(session, plan.maxRunningSessions, record, journal, caps.retainedLimit())
        return when (outcome) {
            KeyedCreateOutcome.Created -> KeyedCreation(session, replayed = false)
            is KeyedCreateOutcome.Replayed -> KeyedCreation(outcome.session, replayed = true)
            KeyedCreateOutcome.Conflict -> throw CloudSessionIdempotencyConflictException(
                "This Idempotency-Key was already used with a different request, or its session no longer exists.",
            )
            KeyedCreateOutcome.AtCap -> throw CloudSessionLimitException(plan.maxRunningSessions)
        }
    }

    private suspend fun created(caller: Caller, payload: CreateCloudSessionPayload): CloudSession {
        val (session, plan) = drafted(caller, payload)
        val journal = caller.journal(session, CREATED)
        if (!repository.createWithinCap(session, plan.maxRunningSessions, journal, caps.retainedLimit())) {
            throw CloudSessionLimitException(plan.maxRunningSessions)
        }
        return session
    }

    /** The session the request asks for, validated, with the plan it is created under. */
    private suspend fun drafted(caller: Caller, payload: CreateCloudSessionPayload): Pair<CloudSession, CloudPlan> {
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
        val mode = mode(agent, payload.mode)
        payload.effort?.let(CloudConversationRules::validateEffort)
        val prompt = payload.prompt?.takeIf { it.isNotBlank() }
        ensureValid(prompt == null || prompt.length <= MAX_PROMPT_LENGTH) {
            "The prompt must be at most $MAX_PROMPT_LENGTH characters."
        }
        // The profile's assignment resolves for the owner: an administrator's role never lends it to another member.
        val profile = profiles.choose(caller.copy(admin = caller.memberAdmin), payload.profileId, payload.image)
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
            profile = profile,
            mode = mode,
            effort = payload.effort,
            automationId = payload.automationId,
        )
        return attributed(session, caller) to plan
    }

    /**
     * A start asked again under the same id (a workflow node after a restart) finds the first one, before any rule
     * that could refuse it now (its profile, the plan's cap or hours).
     */
    private suspend fun existing(caller: Caller, payload: CreateCloudSessionPayload): CloudSession? {
        val existing = payload.id?.let { repository.get(caller.orgId, it) } ?: return null
        ensureValid(existing.ownerId == caller.memberId) { "Another member's session has this id." }
        return existing
    }

    /**
     * The session with who created it. Created on someone's behalf, it runs the creator's instructions: no GitHub
     * token until its owner allows it (TeamCode's audit M2), so one that requires GitHub is the owner's to create.
     */
    private fun attributed(session: CloudSession, caller: Caller): CloudSession {
        if (!caller.onBehalf) return session.copy(createdBy = caller.attribution)
        if (session.environment?.requireGitHub == true) throw GitHubOwnerConsentException()
        return session.copy(githubAccess = GitHubAccessMode.NONE, createdBy = caller.attribution)
    }

    /** The mode asked for: a terminal by default, a conversation only for an agent that runs headless. */
    private fun mode(agent: AgentKind, wire: String?): SessionMode {
        val mode = if (wire == null) SessionMode.TERMINAL else SessionMode.fromWire(wire)
        if (mode == null || (mode == SessionMode.CONVERSATION && agent !in SessionMode.CONVERSATION_AGENTS)) {
            throw CloudConversationRules.invalid(
                "invalid_mode",
                "Use mode terminal, or conversation with the claude or codex agent.",
            )
        }
        return mode
    }

    /** The name of a session asked without one: its repository's, the first of its recipe's, or none. */
    private fun defaultName(payload: CreateCloudSessionPayload): String? {
        val repositories = payload.environment?.repositories.orEmpty()
        val url = when {
            payload.repository != null -> payload.repository.url
            repositories.isNotEmpty() -> repositories.first().url
            else -> return null
        }
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
        /** How long a creation's `Idempotency-Key` replays it (TeamCode's `IDEMPOTENCY_MILLIS`). */
        private val IDEMPOTENCY_RETENTION = 24.hours
        const val MAX_NAME_LENGTH = 120
        const val MAX_PROMPT_LENGTH = 20_000
        const val MAX_ECHO = 32
        private val MODEL = Regex("[A-Za-z0-9._:/@-]{1,100}")
        private val GITHUB_REPOSITORY = Regex(
            "https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}(\\.git)?",
        )
        private val GIT_REF = Regex("[A-Za-z0-9._/-]{1,200}")
        const val CREATED = "cloud.session.created"
    }
}

/**
 * The audit entry of a write of a session made through the API (`runners.cloud.onbehalf.v1`, TeamCode's
 * `CloudWrite`), which the repository writes in the write's transaction: the actor is the credential, with the
 * effective member and the credential's id ([onBehalfDetails]).
 *
 * @param session The session written.
 * @param event What happened (`cloud.session.paused`).
 *
 * @return The entry, or null for a write of the server itself (a workflow run, an automation), not journaled here.
 */
internal fun Caller.journal(session: CloudSession, event: String): OrgAuditRecord? {
    val actor = principal ?: return null
    val details = onBehalfDetails("owner" to session.ownerId)
    return OrgAuditRecord(orgId, actor, event, "cloud_session", session.id.toString(), details)
}

/**
 * The details every cloud write journals (`runners.cloud.onbehalf.writes.v1`): the member it acted for and the id of
 * the credential it presented, then the write's own facts.
 *
 * @param facts The write's own facts, by name.
 *
 * @return The details.
 */
internal fun Caller.onBehalfDetails(vararg facts: Pair<String, Any?>): Map<String, String> =
    auditDetails("effective_member_id" to effectiveMemberId, "credential_id" to credentialId, *facts)

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
            SessionState.PAUSED, SessionState.TRANSFERRING, SessionState.TERMINATED, SessionState.FAILED -> it
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
     * @param kinds The kinds listed: cloud sessions only by default, so that a client from before remote sessions
     *   never lists one it cannot show.
     * @param owner The owner asked for (`runners.cloud.sessions.owner.v1`, TeamCode's `owner`): the caller's member
     *   id, or null for the caller.
     * @param states The statuses listed (TeamCode's `state`); every status when empty.
     *
     * @return The sessions, newest first.
     *
     * @throws InvalidCloudSessionRequestException When the limit is out of bounds.
     * @throws CloudSessionsOwnerForbiddenException When [owner] names anyone else (another member, a login, `all`),
     *   whatever the caller's role.
     */
    @Suppress("LongParameterList") // The list's query: the caller, the page and each filter of TeamCode's.
    suspend operator fun invoke(
        caller: Caller,
        before: Uuid?,
        limit: Int?,
        kinds: Set<SessionKind> = setOf(SessionKind.CLOUD),
        owner: String? = null,
        states: Set<SessionState> = emptySet(),
    ): List<CloudSession>
}

/**
 * [ListCloudSessionsUseCase] on the repository.
 *
 * @param repository The sessions.
 */
class ListCloudSessionsUseCaseImpl(private val repository: CloudSessionsRepository) : ListCloudSessionsUseCase {
    @Suppress("LongParameterList") // As the interface's.
    override suspend fun invoke(
        caller: Caller,
        before: Uuid?,
        limit: Int?,
        kinds: Set<SessionKind>,
        owner: String?,
        states: Set<SessionState>,
    ): List<CloudSession> {
        val size = limit ?: DEFAULT_PAGE
        ensureValid(size in 1..MAX_PAGE) { "The limit must be between 1 and $MAX_PAGE." }
        // Nobody lists other members' sessions, administrators included: a session is reached by its owner, or after
        // a transfer (TeamCode's rule).
        if (owner != null && owner != caller.memberId) throw CloudSessionsOwnerForbiddenException()
        // A `before` that names no session of the organization (forged, or another's) is a 400, not an empty page.
        val page = repository.list(caller.orgId, caller.memberId, before, size, kinds, states)
        return Pages.checked("before", before, page) {
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
     * @throws RemoteSessionException `local_session` (409) for a local session.
     */
    suspend fun pause(caller: Caller, id: Uuid): CloudSession

    /**
     * Asks for a resume, within the plan: a new execution starts on the kept workspace. A remote session its device
     * refused ([SessionStateMachine.reopenable]) is reopened instead: `paused`, wanted `running`, journaled as
     * `cloud.session.reopened`, and its device's queue hands it out again (TeamCode #282).
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
     * @throws RemoteSessionException `local_session` (409) for a local session.
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
 * [ChangeCloudSessionUseCase] on the repository and the plan. An API call that changes the session is journaled in the
 * change's transaction.
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
        // A local session lives as long as its launch: only its host going silent pauses it.
        if (session.kind == SessionKind.LOCAL) throw LocalSessionRules.localSession()
        if (session.desiredState == DesiredState.PAUSED) return session
        val journal = caller.journal(session, "cloud.session.paused")
        return repository.setDesiredState(caller.orgId, id, DesiredState.PAUSED, clock.now(), journal)
            ?: throw CloudSessionNotFoundException()
    }

    override suspend fun resume(caller: Caller, id: Uuid): CloudSession {
        val session = repository.ownedBy(caller, id)
        // A session the device refused is reopened by resuming it (TeamCode #282): it never ran, and its conversation
        // is still on the device. One reopened or deleted meanwhile goes through the resume below.
        if (!SessionStateMachine.reopenable(session)) return resumeOpen(caller, id, session)
        val journal = caller.journal(session, "cloud.session.reopened")
        return repository.reopen(caller.orgId, id, clock.now(), journal)
            ?: resumeOpen(caller, id, repository.ownedBy(caller, id))
    }

    @Suppress("ThrowsCount") // The resume's refusals, one each, as before the reopen was split out.
    private suspend fun resumeOpen(caller: Caller, id: Uuid, session: CloudSession): CloudSession {
        if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) {
            throw CloudSessionStateException("This session is over.")
        }
        // Its own launch takes the next epoch (POST /cloud/sessions/{id}/host): nobody else resumes it.
        if (session.kind == SessionKind.LOCAL) throw LocalSessionRules.localSession()
        // An accepted transfer pauses the session; only the transfer itself resumes it, for the recipient.
        if (session.transferId != null || session.state == SessionState.TRANSFERRING) {
            throw SessionTransferringException()
        }
        if (session.desiredState == DesiredState.RUNNING) return session
        val plan = plans.planFor(caller.orgId)
        val now = clock.now()
        // A remote session runs on its owner's device: no hour of the plan is used.
        if (session.kind == SessionKind.CLOUD) requireHoursLeft(usage, plan, caller.orgId, now)
        val journal = caller.journal(session, "cloud.session.resumed")
        val outcome = repository.resumeWithinCap(caller.orgId, id, plan.maxRunningSessions, now, journal)
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
        val journal = caller.journal(session, "cloud.session.deleted")
        return repository.setDesiredState(caller.orgId, id, DesiredState.TERMINATED, clock.now(), journal)
            ?: throw CloudSessionNotFoundException()
    }
}
