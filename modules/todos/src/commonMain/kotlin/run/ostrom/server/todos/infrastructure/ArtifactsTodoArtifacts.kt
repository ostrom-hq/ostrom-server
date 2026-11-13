// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.todos.domain.TodoArtifacts
import kotlin.uuid.Uuid

/**
 * [TodoArtifacts] on the artifacts module: the latest version of each artifact an item names, read in its
 * organization by primary key. An item names at most one artifact per check, so the reads are bounded by the items
 * asked about, not by the organization's artifacts.
 *
 * @param artifacts The artifacts module's storage.
 */
internal class ArtifactsTodoArtifacts(private val artifacts: ArtifactsRepository) : TodoArtifacts {
    override suspend fun versions(orgId: Uuid, artifactIds: Collection<String>): Map<String, Int> =
        artifactIds.mapNotNull { id ->
            Uuid.parseOrNull(id)?.let { artifacts.get(orgId, it) }?.let { id to it.latestVersion }
        }.toMap()
}
