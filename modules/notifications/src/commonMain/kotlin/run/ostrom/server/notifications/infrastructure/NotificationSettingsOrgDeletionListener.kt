// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import kotlin.uuid.Uuid

/**
 * The members' switches of a deleted organization are erased at once, and again at the purge (both idempotent).
 *
 * @param repository The switches.
 */
class NotificationSettingsOrgDeletionListener(private val repository: NotificationSettingsRepository) :
    OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        repository.deleteAll(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        repository.deleteAll(orgId)
    }
}
