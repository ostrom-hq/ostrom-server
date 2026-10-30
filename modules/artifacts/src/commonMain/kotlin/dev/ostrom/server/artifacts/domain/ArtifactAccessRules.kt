// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.domain

import kotlin.uuid.Uuid

/**
 * Who may do what with an artifact. Reading: the owner, an administrator, every member for `team` and `link`,
 * and the members it is shared with. Publishing a version: the owner. Changing access, sharing and deleting: the
 * owner or an administrator. Another organization's artifact is never visible.
 *
 * @param repository Where shares are read.
 */
internal class ArtifactAccessRules(private val repository: ArtifactsRepository) {
    /**
     * Reads an artifact the caller may read.
     *
     * @throws ArtifactNotFoundException When it does not exist or the caller may not read it.
     */
    suspend fun readable(caller: ArtifactCaller, id: Uuid): Artifact {
        val artifact = repository.get(caller.orgId, id) ?: throw ArtifactNotFoundException()
        val reads = artifact.ownerId == caller.memberId || caller.admin ||
            artifact.access != ArtifactAccess.PERSONAL || caller.memberId in repository.members(artifact.id)
        if (!reads) throw ArtifactNotFoundException()
        return artifact
    }

    /**
     * Reads an artifact the caller may publish to.
     *
     * @throws ArtifactNotFoundException When they may not read it.
     * @throws ArtifactNotWritableException When they read it but do not own it.
     */
    suspend fun writable(caller: ArtifactCaller, id: Uuid): Artifact {
        val artifact = readable(caller, id)
        if (artifact.ownerId != caller.memberId) throw ArtifactNotWritableException()
        return artifact
    }

    /**
     * Reads an artifact whose access, shares or existence the caller may change.
     *
     * @throws ArtifactNotFoundException When they may not read it.
     * @throws ArtifactNotWritableException When they read it but neither own it nor administer the organization.
     */
    suspend fun manageable(caller: ArtifactCaller, id: Uuid): Artifact {
        val artifact = readable(caller, id)
        if (artifact.ownerId != caller.memberId && !caller.admin) throw ArtifactNotWritableException()
        return artifact
    }
}
