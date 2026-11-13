// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.PushDevicesRepository
import kotlin.uuid.Uuid

/**
 * The notifications' part of an organization's deletion: its phones are removed at once, so nothing is pushed for it
 * any more, and again at the purge (both calls are idempotent).
 *
 * @param repository The phones.
 */
class PushDevicesOrgDeletionListener(private val repository: PushDevicesRepository) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        repository.deleteAll(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        repository.deleteAll(orgId)
    }
}
