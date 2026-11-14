// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAccess
import run.ostrom.server.integrations.github.domain.GitHubAccessResolver
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.oauthApp
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import kotlin.uuid.Uuid

/**
 * The runners module's GitHub tokens, from the members' links: the session owner's user-to-server token, refreshed
 * by [GitHubAccessResolver]. Never an installation token.
 *
 * @param apps The org's GitHub App.
 * @param resolver The links' tokens.
 */
class LinkedRunnerGitHubTokens(
    private val apps: GitHubAppSource,
    private val resolver: GitHubAccessResolver,
) : RunnerGitHubTokens {
    override suspend fun available(): Boolean = apps.current().oauthApp() != null

    override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken {
        // A session owned by something else than a member (an id that is not a UUID) has no link.
        val member = Uuid.parseOrNull(memberId) ?: return RunnerGitHubToken.NotLinked
        return when (val access = resolver.access(orgId, member)) {
            is GitHubAccess.Linked ->
                RunnerGitHubToken.Linked(access.token, access.expiresAt, access.login, access.userId)
            GitHubAccess.NotLinked -> RunnerGitHubToken.NotLinked
            GitHubAccess.Unavailable -> RunnerGitHubToken.Unavailable
        }
    }
}
