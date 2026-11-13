// SPDX-License-Identifier: Apache-2.0
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
     * Reads an artifact the caller may read; a draft is read by nobody, as if it did not exist.
     *
     * @throws ArtifactNotFoundException When it does not exist or the caller may not read it.
     */
    suspend fun readable(caller: ArtifactCaller, id: Uuid): Artifact =
        visible(caller, id)?.takeIf { !it.draft } ?: throw ArtifactNotFoundException()

    // The artifact when the caller reads it, a draft included (only its owner does: drafts are personal).
    private suspend fun visible(caller: ArtifactCaller, id: Uuid): Artifact? =
        repository.get(caller.orgId, id)?.takeIf { reads(caller, it) }

    /**
     * Whether the caller reads an artifact of their organization (the caller's own: read in it, or found by its id).
     *
     * @return True for its owner, any member for `team` and `link`, and the members it is shared with.
     */
    suspend fun reads(caller: ArtifactCaller, artifact: Artifact): Boolean =
        artifact.ownerId == caller.memberId || artifact.access != ArtifactAccess.PERSONAL ||
            caller.memberId in repository.members(artifact.orgId, artifact.id)

    /**
     * Reads an artifact the caller may change: publish to, retitle, share, change the access of or delete; their own
     * draft included (an upload's, never committed).
     *
     * @throws ArtifactNotFoundException When they may not read it.
     * @throws ArtifactNotWritableException When they read it but do not own it, whatever their role.
     */
    suspend fun owned(caller: ArtifactCaller, id: Uuid): Artifact {
        val artifact = visible(caller, id) ?: throw ArtifactNotFoundException()
        if (artifact.ownerId != caller.memberId) throw ArtifactNotWritableException()
        return artifact
    }
}
