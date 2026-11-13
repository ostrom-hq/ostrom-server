// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.domain.WebhooksRepository
import run.ostrom.server.kernel.module.OrgDeletionListener
import kotlin.uuid.Uuid

/**
 * Erases a deleted organization's journal and webhooks once its deletion is final. Nothing stops before: a deleted
 * organization's members no longer reach the feed nor its webhooks, and its events age out within the retention.
 *
 * @param events The journal.
 * @param webhooks The webhooks and their deliveries.
 */
class EventsOrgDeletionListener(
    private val events: OrgEventsRepository,
    private val webhooks: WebhooksRepository,
) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) = Unit

    override suspend fun purge(orgId: Uuid) {
        webhooks.erase(orgId)
        events.erase(orgId)
    }
}
