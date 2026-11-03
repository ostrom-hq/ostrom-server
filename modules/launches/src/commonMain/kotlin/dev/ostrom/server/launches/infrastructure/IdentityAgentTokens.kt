// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.identity.domain.IssueAgentTokenUseCase
import dev.ostrom.server.identity.domain.RevokeAgentTokensUseCase
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.services.AgentTokenService
import kotlin.time.Duration

/**
 * [AgentTokenService] on the identity module's agent tokens: the token's subject is the launch id, its organization the
 * launcher's, its only scope `agent:session`.
 *
 * @param issuer The identity module's issuer.
 * @param revoker The identity module's revocation of an agent session's tokens: a launch is the agent session.
 */
class IdentityAgentTokens(
    private val issuer: IssueAgentTokenUseCase,
    private val revoker: RevokeAgentTokensUseCase,
) : AgentTokenService {
    override suspend fun issue(launcher: Launcher, launchId: String, lifetime: Duration): String =
        issuer(launcher.userId, launcher.orgId, launchId, lifetime).secret

    override suspend fun revoke(launchId: String): Long = revoker(launchId)
}
