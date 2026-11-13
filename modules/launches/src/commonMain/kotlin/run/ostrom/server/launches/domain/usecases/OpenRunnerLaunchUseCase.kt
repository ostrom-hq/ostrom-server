// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.launches.domain.models.CreatedLaunch
import run.ostrom.server.launches.domain.models.InvalidLaunchException
import run.ostrom.server.launches.domain.models.NewLaunch
import run.ostrom.server.launches.domain.models.NoLaunchOrganizationException
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudSession
import kotlin.uuid.Uuid

/**
 * Opens the launch of a cloud session, asked by its runner (decision 13:23): the agent in the pod gets an agent token
 * like the CLI's, and the launch remembers the cloud session, so that only that session's runner reports its checks
 * as run by Ostrom (`isolated_runner`).
 */
interface OpenRunnerLaunchUseCase {
    /**
     * Opens the launch, for the cloud session's owner and under the organization's policy.
     *
     * @param session The cloud session of the runner's current execution.
     *
     * @return The launch and its agent token's secret.
     *
     * @throws InvalidLaunchException When the session runs a shell, which no launch covers.
     * @throws NoLaunchOrganizationException When its owner is no longer a member.
     * @throws run.ostrom.server.launches.domain.models.LaunchRefusedException When the policy refuses the owner.
     */
    suspend operator fun invoke(session: CloudSession): CreatedLaunch
}

/**
 * [OpenRunnerLaunchUseCase] on [CreateLaunchUseCase]: the same checks as a launch of the CLI.
 *
 * @param launchers Resolves the owner.
 * @param createLaunch Creates the launch.
 */
class OpenRunnerLaunchUseCaseImpl(
    private val launchers: LauncherResolver,
    private val createLaunch: CreateLaunchUseCase,
) : OpenRunnerLaunchUseCase {
    override suspend fun invoke(session: CloudSession): CreatedLaunch {
        val agent = agentOf(session.agent) ?: throw InvalidLaunchException("A shell session runs no agent to launch.")
        val owner = Uuid.parseOrNull(session.ownerId)
        val launcher = if (owner == null) null else launchers.owner(session.orgId, owner)
        if (launcher == null) throw NoLaunchOrganizationException()
        return createLaunch(launcher, NewLaunch(session.repository?.url, agent, session.model, session.id))
    }

    // The names `ostrom <agent>` reports (the CLI's `ostromName`): Claude Code is `claude-code`.
    private fun agentOf(kind: AgentKind): String? = when (kind) {
        AgentKind.CLAUDE -> "claude-code"
        AgentKind.SHELL -> null
        else -> kind.wire
    }
}
