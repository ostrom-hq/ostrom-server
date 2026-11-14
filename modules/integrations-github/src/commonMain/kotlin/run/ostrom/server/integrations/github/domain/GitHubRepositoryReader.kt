// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryListing
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RepositoryRef
import run.ostrom.server.rules.domain.models.RepositoryTree
import run.ostrom.server.rules.domain.services.RepositoryReader
import kotlin.uuid.Uuid

/**
 * The rules module's [RepositoryReader] on GitHub: an organization reads through its own installation of the App,
 * and only the repositories of the account it is installed on. A repository of another account is refused before
 * any call, so one organization's token never reads another's code. Everything is read-only.
 *
 * @param installations Each organization's installation.
 * @param directory The repositories an installation is granted.
 * @param contents Their files and branch protection.
 * @param webUrl GitHub's web address, for the repositories' pages, read at each use.
 */
class GitHubRepositoryReader(
    private val installations: GitHubRepository,
    private val directory: GitHubDirectoryApi,
    private val contents: GitHubContentsApi,
    private val webUrl: () -> String,
) : RepositoryReader {
    override suspend fun repositories(orgId: Uuid): RepositoryListing {
        val installation = installations.installation(orgId) ?: return RepositoryListing.NotConnected(NOT_INSTALLED)
        val repos = github { directory.repositories(installation.installationId) }
        return RepositoryListing.Connected(repos.map { ref(installation.account, it) })
    }

    override suspend fun tree(orgId: Uuid, repo: RepositoryRef): RepositoryTree {
        val installation = installed(orgId, repo)
        return github { contents.tree(installation.installationId, installation.path(repo), repo.defaultBranch) }
    }

    override suspend fun file(orgId: Uuid, repo: RepositoryRef, path: String): String? {
        val installation = installed(orgId, repo)
        return github { contents.file(installation.installationId, installation.path(repo), repo.defaultBranch, path) }
    }

    override suspend fun protection(orgId: Uuid, repo: RepositoryRef): BranchProtection? {
        val installation = installed(orgId, repo)
        return github {
            contents.protection(installation.installationId, installation.path(repo), repo.defaultBranch)
        }
    }

    // The organization's own installation, and only for a repository of the account it is installed on.
    private suspend fun installed(orgId: Uuid, repo: RepositoryRef): GitHubInstallation =
        installations.installation(orgId)
            ?.takeIf { it.account.equals(repo.owner, ignoreCase = true) && isRepositoryName(path(it.account, repo)) }
            ?: throw RepositoryReadException(NOT_ITS_OWN)

    // The repository as the installation's account names it, whatever the case the analysis was given.
    private fun GitHubInstallation.path(repo: RepositoryRef) = path(account, repo)

    private fun path(account: String, repo: RepositoryRef) = "$account/${repo.name}"

    private fun ref(account: String, repo: GitHubRepo) = RepositoryRef(
        owner = account,
        name = repo.name,
        defaultBranch = repo.defaultBranch,
        archived = repo.archived,
        description = repo.description,
        pushedAt = repo.pushedAt?.toString(),
        webUrl = "${webUrl()}/$account/${repo.name}",
    )

    // GitHub's sentence becomes the analysis's: it names the call, never a token.
    private inline fun <T> github(read: () -> T): T = try {
        read()
    } catch (error: GitHubCallException) {
        throw RepositoryReadException("${error.message}", error)
    }

    private companion object {
        const val NOT_INSTALLED = "The Ostrom GitHub App is not installed on this organization: install it from " +
            "the GitHub page of the console, then start the analysis again."
        const val NOT_ITS_OWN = "This repository is not one the organization's GitHub installation sees."
    }
}
