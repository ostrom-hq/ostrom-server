// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.kernel.workflows.WorkflowDirectory
import run.ostrom.server.launches.domain.models.CreatedLaunch
import run.ostrom.server.launches.domain.models.InvalidLaunchException
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.domain.models.LaunchRateLimitedException
import run.ostrom.server.launches.domain.models.LaunchRefusedException
import run.ostrom.server.launches.domain.models.LaunchWorkflowNotFoundException
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.models.NewLaunch
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.domain.services.AgentTokenService
import run.ostrom.server.launches.domain.services.HooksCheckRules
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Creates a launch and its agent token (contract `createLaunch`, audit A12/A13): the CLI never puts the person's own
 * token in the agent's environment, only this one, which acts on the launch's sessions alone and expires with it.
 */
interface CreateLaunchUseCase {
    /**
     * Creates the launch.
     *
     * @param launcher The person, from their credential.
     * @param request What the CLI asks for.
     *
     * @return The launch and its agent token's secret.
     *
     * @throws InvalidLaunchException When the repository, the agent's name or the model's is malformed.
     * @throws LaunchRefusedException When the organization's policy refuses the person or the model.
     * @throws LaunchRateLimitedException When the person or the organization launched too many agents lately.
     * @throws LaunchWorkflowNotFoundException When the workflow it names is not one of the organization's.
     */
    suspend operator fun invoke(launcher: Launcher, request: NewLaunch): CreatedLaunch
}

/**
 * [CreateLaunchUseCase] on the repository and the identity module's agent tokens.
 *
 * @param repository The launches.
 * @param policy The organization's policy, checked again here.
 * @param tokens Issues the agent token.
 * @param clock The clock.
 * @param hooks The wait for the hooks and whether to stop an agent without them, told to the CLI.
 * @param limit How many launches a window takes, per person and per organization.
 * @param workflows The organization's workflows, which a launch may name; null, none is known.
 */
@Suppress("LongParameterList") // One parameter per port a launch reads; the limit and the workflows have defaults.
class CreateLaunchUseCaseImpl(
    private val repository: LaunchesRepository,
    private val policy: LaunchPolicy,
    private val tokens: AgentTokenService,
    private val clock: Clock,
    private val hooks: HooksCheckRules,
    private val limit: LaunchLimit = LaunchLimit.DEFAULT,
    private val workflows: WorkflowDirectory? = null,
) : CreateLaunchUseCase {
    override suspend fun invoke(launcher: Launcher, request: NewLaunch): CreatedLaunch {
        val repo = request.repo
        if (repo != null && !wellFormed(repo, MAX_REPO)) {
            throw InvalidLaunchException("repo: 1 to $MAX_REPO characters, without control characters.")
        }
        val workflowId = request.workflowId
        workflowId?.takeUnless { wellFormed(it, MAX_WORKFLOW_ID) }?.let {
            throw InvalidLaunchException("workflowId: 1 to $MAX_WORKFLOW_ID characters, without control characters.")
        }
        // A malformed name is the client's mistake (400); a refusal, the organization's answer (403).
        policy.malformed(request.agent, request.model)?.let { throw InvalidLaunchException(it) }
        policy.refusal(launcher, request.agent, request.model)?.let { throw LaunchRefusedException(it) }
        // A workflow of another organization is answered like none: nothing leaks.
        workflowId?.takeIf { workflows?.name(launcher.orgId, it) == null }?.let {
            throw LaunchWorkflowNotFoundException()
        }
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val launch = Launch(
            id = "lch_" + Uuid.random().toHexString(),
            orgId = launcher.orgId,
            userId = launcher.userId,
            dev = launcher.login,
            repo = repo,
            agent = request.agent,
            model = request.model,
            createdAt = now,
            expiresAt = now + LIFETIME,
            cloudSessionId = request.cloudSessionId,
            workflowId = workflowId,
        )
        // The launch first: its token never exists without the launch it is scoped to.
        // Counted before any token exists: a refused launch issues nothing.
        repository.create(launch, limit)?.let { throw LaunchRateLimitedException(it) }
        val hooksCheck = hooks.check(launcher.orgId, repo)
        return CreatedLaunch(launch, tokens.issue(launcher, launch.id, LIFETIME), hooksCheck)
    }

    private fun wellFormed(value: String, max: Int) = value.length in 1..max && value.none { it.isISOControl() }

    /** The bounds of a launch. */
    companion object {
        /** How long a launch and its agent token live: the identity module's longest agent token (a test checks). */
        val LIFETIME: Duration = 24.hours

        /** The longest repository name. */
        const val MAX_REPO: Int = 200

        /** The longest workflow id a launch names. */
        const val MAX_WORKFLOW_ID: Int = 200
    }
}
