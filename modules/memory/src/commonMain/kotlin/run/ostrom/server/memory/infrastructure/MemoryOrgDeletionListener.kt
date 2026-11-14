// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.memory.domain.MemoryRepository
import kotlin.uuid.Uuid

/**
 * Erases a deleted organization's memory once its 30 days are over (TeamCode deletes a team's memory with the team).
 * Nothing runs for it in between: its members lost access at once.
 *
 * @param repository The memory's storage.
 */
internal class MemoryOrgDeletionListener(private val repository: MemoryRepository) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) = Unit

    override suspend fun purge(orgId: Uuid) = repository.purge(orgId)
}
