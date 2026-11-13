// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.launches.domain.models.LaunchNotFoundException
import run.ostrom.server.launches.domain.services.AgentTokenService

/**
 * Ends a launch when `ostrom <agent>` ends (AUDIT-SECU-RUNNER-2 R2b): its agent token is revoked at once instead of
 * living out its [CreateLaunchUseCase.LIFETIME]. Only the launch's own agent token ends it: the CLI holds it until the
 * end, and no other credential needs to.
 */
interface EndLaunchUseCase {
    /**
     * Ends the launch.
     *
     * @param callerLaunch The launch of the caller's agent token, or null when the caller is no live agent token.
     * @param launchId The launch to end.
     *
     * @throws LaunchNotFoundException When the caller is not that launch's agent token.
     */
    suspend operator fun invoke(callerLaunch: String?, launchId: String)
}

/**
 * [EndLaunchUseCase] on the identity module's agent tokens.
 *
 * @param tokens Revokes the launch's agent tokens.
 */
class EndLaunchUseCaseImpl(private val tokens: AgentTokenService) : EndLaunchUseCase {
    override suspend fun invoke(callerLaunch: String?, launchId: String) {
        if (callerLaunch != launchId) throw LaunchNotFoundException()
        tokens.revoke(launchId)
    }
}
