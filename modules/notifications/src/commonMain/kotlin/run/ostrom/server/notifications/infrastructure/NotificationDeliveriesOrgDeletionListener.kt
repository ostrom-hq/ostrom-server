// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.NotificationDeliveriesRepository
import kotlin.uuid.Uuid

/**
 * Erases a deleted organization's queued and finished deliveries, their texts with them.
 *
 * @param repository The queue.
 */
class NotificationDeliveriesOrgDeletionListener(private val repository: NotificationDeliveriesRepository) :
    OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        repository.deleteAll(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        repository.deleteAll(orgId)
    }
}
