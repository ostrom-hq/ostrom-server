// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Changes an artifact's title and access, its shares, or deletes it. */
interface ManageArtifactUseCase {
    /**
     * Changes the title, the access, or both; the owner only. Turning to `link` mints a new public link; leaving it
     * ends the link.
     *
     * @return The artifact.
     *
     * @throws ArtifactPersonRequiredException When the access changes and no person asks.
     */
    suspend fun update(caller: ArtifactCaller, id: Uuid, title: String?, access: String?): Artifact

    /**
     * The members the artifact is shared with.
     *
     * @return Their ids.
     */
    suspend fun members(caller: ArtifactCaller, id: Uuid): List<String>

    /**
     * Shares the artifact with a member of the organization, or stops; the owner only.
     *
     * @param member The member's id.
     * @param share True to share, false to stop.
     */
    suspend fun share(caller: ArtifactCaller, id: Uuid, member: String, share: Boolean)

    /**
     * Deletes the artifact, its versions and files; the owner only, and only when a person asks.
     *
     * @throws ArtifactNotFoundException When it is unknown or unreadable.
     * @throws ArtifactPersonRequiredException When no person asks.
     */
    suspend fun delete(caller: ArtifactCaller, id: Uuid)
}

/** [ManageArtifactUseCase]. */
internal class ManageArtifactUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val clock: Clock,
) : ManageArtifactUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun update(caller: ArtifactCaller, id: Uuid, title: String?, access: String?): Artifact {
        val artifact = rules.owned(caller, id)
        val newAccess = if (access == null) artifact.access else parseAccess(access)
        if (newAccess != artifact.access && !caller.person) throw ArtifactPersonRequiredException()
        val slug = when {
            newAccess != ArtifactAccess.LINK -> null
            artifact.access == ArtifactAccess.LINK -> artifact.linkSlug
            else -> linkSlugFor(newAccess)
        }
        val updated = artifact.copy(
            title = if (title == null) artifact.title else ArtifactRules.title(title),
            access = newAccess,
            linkSlug = slug,
            updatedAt = clock.now(),
        )
        repository.updateSettings(updated)
        return updated
    }

    override suspend fun members(caller: ArtifactCaller, id: Uuid): List<String> {
        rules.readable(caller, id)
        return repository.members(caller.orgId, id)
    }

    override suspend fun share(caller: ArtifactCaller, id: Uuid, member: String, share: Boolean) {
        rules.owned(caller, id)
        ensureValid(Uuid.parseOrNull(member) != null) { "Name the member by their id." }
        repository.setMember(caller.orgId, id, member, share)
    }

    override suspend fun delete(caller: ArtifactCaller, id: Uuid) {
        rules.owned(caller, id)
        if (!caller.person) throw ArtifactPersonRequiredException()
        repository.delete(caller.orgId, id)
    }
}
