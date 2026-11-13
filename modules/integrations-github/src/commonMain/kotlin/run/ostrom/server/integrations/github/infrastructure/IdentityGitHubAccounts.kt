// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.integrations.github.domain.GitHubAccounts

/**
 * [GitHubAccounts] and the gates' [GateGitHubAccounts] over the identity module: the user who signed in with a GitHub
 * account, and the GitHub accounts a user linked.
 *
 * @param users The identity module's users.
 * @param identities The accounts each user linked.
 */
class IdentityGitHubAccounts(
    private val users: UsersRepository,
    private val identities: LinkedIdentities,
) : GitHubAccounts, GateGitHubAccounts {
    override suspend fun loginOf(githubUserId: Long): String? =
        users.findByIdentity(IdentityProvider.GITHUB.key, githubUserId.toString())?.login

    override suspend fun gitHubIdsOf(login: String): List<Long> =
        identities.subjectsOf(login, IdentityProvider.GITHUB.key).mapNotNull { it.toLongOrNull() }
}
