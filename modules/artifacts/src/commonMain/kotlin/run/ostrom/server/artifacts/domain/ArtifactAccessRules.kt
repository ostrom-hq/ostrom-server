// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import kotlin.uuid.Uuid

/**
 * Who may do what with an artifact (TeamCode #238, `docs/artifacts.md` "Who may do what"). Reading: the owner,
 * every member for `team` and `link`, and the members it is shared with; a role opens nobody's personal artifact, an
 * owner or an administrator of the organization included. Publishing a version, changing access, sharing and
 * deleting: the owner only. Another organization's artifact is never visible.
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
        val reads = artifact.ownerId == caller.memberId || artifact.access != ArtifactAccess.PERSONAL ||
            caller.memberId in repository.members(artifact.orgId, artifact.id)
        if (!reads) throw ArtifactNotFoundException()
        return artifact
    }

    /**
     * Reads an artifact the caller may change: publish to, retitle, share, change the access of or delete.
     *
     * @throws ArtifactNotFoundException When they may not read it.
     * @throws ArtifactNotWritableException When they read it but do not own it, whatever their role.
     */
    suspend fun owned(caller: ArtifactCaller, id: Uuid): Artifact {
        val artifact = readable(caller, id)
        if (artifact.ownerId != caller.memberId) throw ArtifactNotWritableException()
        return artifact
    }
}
