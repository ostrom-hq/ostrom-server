// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.rules.domain.models.BranchProtection
import dev.ostrom.server.rules.domain.models.RepositoryListing
import dev.ostrom.server.rules.domain.models.RepositoryReadException
import dev.ostrom.server.rules.domain.models.RepositoryRef
import dev.ostrom.server.rules.domain.models.RepositoryTree
import dev.ostrom.server.rules.domain.services.RepositoryReader
import kotlin.uuid.Uuid

/**
 * The reader while no GitHub module binds one: no organization is connected, so every analysis fails at once with a
 * reason the console shows. Nothing pretends to read.
 */
class NoRepositoryReader : RepositoryReader {
    override suspend fun repositories(orgId: Uuid): RepositoryListing = RepositoryListing.NotConnected(REASON)

    override suspend fun tree(orgId: Uuid, repo: RepositoryRef): RepositoryTree = throw RepositoryReadException(REASON)

    override suspend fun file(orgId: Uuid, repo: RepositoryRef, path: String): String? =
        throw RepositoryReadException(REASON)

    override suspend fun protection(orgId: Uuid, repo: RepositoryRef): BranchProtection? =
        throw RepositoryReadException(REASON)

    /** The sentence the console shows. */
    companion object {
        /** Why nothing can be read. */
        const val REASON: String = "GitHub is not connected on this server: install the Ostrom GitHub App on the " +
            "organization, then start the analysis again."
    }
}
