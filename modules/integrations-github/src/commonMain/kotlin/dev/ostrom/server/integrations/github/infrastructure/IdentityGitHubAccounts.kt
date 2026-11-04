// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.gates.domain.services.GateGitHubAccounts
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.LinkedIdentities
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.integrations.github.domain.GitHubAccounts

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
