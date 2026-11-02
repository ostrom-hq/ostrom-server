// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.usecases

import dev.ostrom.server.launches.domain.models.CreatedLaunch
import dev.ostrom.server.launches.domain.models.InvalidLaunchException
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.models.LaunchRefusedException
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.models.NewLaunch
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.domain.services.AgentTokenService
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
 */
class CreateLaunchUseCaseImpl(
    private val repository: LaunchesRepository,
    private val policy: LaunchPolicy,
    private val tokens: AgentTokenService,
    private val clock: Clock,
) : CreateLaunchUseCase {
    override suspend fun invoke(launcher: Launcher, request: NewLaunch): CreatedLaunch {
        val repo = request.repo
        if (repo != null && !wellFormed(repo)) {
            throw InvalidLaunchException("repo: 1 to $MAX_REPO characters, without control characters.")
        }
        // A malformed name is the client's mistake (400); a refusal, the organization's answer (403).
        policy.malformed(request.agent, request.model)?.let { throw InvalidLaunchException(it) }
        policy.refusal(launcher, request.agent, request.model)?.let { throw LaunchRefusedException(it) }
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
        )
        // The launch first: its token never exists without the launch it is scoped to.
        repository.create(launch)
        return CreatedLaunch(launch, tokens.issue(launcher, launch.id, LIFETIME))
    }

    private fun wellFormed(repo: String) = repo.length in 1..MAX_REPO && repo.none { it.isISOControl() }

    /** The bounds of a launch. */
    companion object {
        /** How long a launch and its agent token live: the identity module's longest agent token (a test checks). */
        val LIFETIME: Duration = 24.hours

        /** The longest repository name. */
        const val MAX_REPO: Int = 200
    }
}
