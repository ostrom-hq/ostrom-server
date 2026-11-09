// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryListing
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RepositoryRef
import run.ostrom.server.rules.domain.models.RepositoryTree
import kotlin.uuid.Uuid

/**
 * Reads an organization's repositories for the analysis, through its GitHub App installation, read-only. Nothing is
 * ever cloned or run: the analysis sees file names, a few small files, and a branch setting. The GitHub module binds
 * the implementation; without it, every organization is [RepositoryListing.NotConnected].
 */
interface RepositoryReader {
    /**
     * Lists the repositories the organization's installation is granted.
     *
     * @param orgId The organization.
     * @return Its repositories, or why none can be read.
     * @throws RepositoryReadException When GitHub does not answer.
     */
    suspend fun repositories(orgId: Uuid): RepositoryListing

    /**
     * Lists the files of a repository's default branch.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @return Its files.
     * @throws RepositoryReadException When GitHub does not answer.
     */
    suspend fun tree(orgId: Uuid, repo: RepositoryRef): RepositoryTree

    /**
     * Reads a small text file of the default branch.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @param path The path, relative to the root.
     * @return Its text, or null when it is absent, binary or larger than the reader reads.
     * @throws RepositoryReadException When GitHub does not answer.
     */
    suspend fun file(orgId: Uuid, repo: RepositoryRef, path: String): String?

    /**
     * Reads how GitHub protects the default branch.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @return The protection, or null when the installation may not read it.
     * @throws RepositoryReadException When GitHub does not answer.
     */
    suspend fun protection(orgId: Uuid, repo: RepositoryRef): BranchProtection?
}
