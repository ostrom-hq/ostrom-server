// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.integrations.github.domain.GitHubAccounts

/**
 * [GitHubAccounts] over the identity module: the user who signed in with that GitHub account.
 *
 * @param users The identity module's users.
 */
class IdentityGitHubAccounts(private val users: UsersRepository) : GitHubAccounts {
    override suspend fun loginOf(githubUserId: Long): String? =
        users.findByIdentity(IdentityProvider.GITHUB.key, githubUserId.toString())?.login
}
