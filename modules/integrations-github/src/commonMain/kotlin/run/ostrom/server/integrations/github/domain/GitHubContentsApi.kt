// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryTree

/**
 * What the analysis of the rules reads in a repository, with the installation's own token restricted to
 * `contents: read` and `metadata: read`: never a write, never a clone.
 */
interface GitHubContentsApi {
    /**
     * Lists the files of a branch, without their contents.
     *
     * @param installationId The installation that sees the repository.
     * @param repo `owner/name`.
     * @param branch The branch.
     * @return Its files; none for an empty repository or a branch GitHub does not know.
     * @throws GitHubCallException When GitHub fails, refuses the token or keeps its rate limit too long.
     */
    suspend fun tree(installationId: Long, repo: String, branch: String): RepositoryTree

    /**
     * Reads a small text file of a branch.
     *
     * @param installationId The installation that sees the repository.
     * @param repo `owner/name`.
     * @param branch The branch.
     * @param path The path, relative to the root.
     * @return Its text, or null when it is absent, a directory, binary or larger than the reader reads.
     * @throws GitHubCallException When GitHub fails, refuses the token or keeps its rate limit too long.
     */
    suspend fun file(installationId: Long, repo: String, branch: String, path: String): String?

    /**
     * Reads how GitHub protects a branch, from its classic protection and the rulesets that apply to it.
     *
     * @param installationId The installation that sees the repository.
     * @param repo `owner/name`.
     * @param branch The branch.
     * @return The protection, or null when the branch cannot be read (absent, or not visible to the installation).
     * @throws GitHubCallException When GitHub fails, refuses the token or keeps its rate limit too long.
     */
    suspend fun protection(installationId: Long, repo: String, branch: String): BranchProtection?
}
