// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.module.OrgDeletionListener
import kotlin.uuid.Uuid

/**
 * Erases a deleted organization's journal once its deletion is final. Nothing stops before: a deleted organization's
 * members no longer reach the feed, and its events age out within the retention anyway.
 *
 * @param events The journal.
 */
class EventsOrgDeletionListener(private val events: OrgEventsRepository) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) = Unit

    override suspend fun purge(orgId: Uuid) = events.erase(orgId)
}
