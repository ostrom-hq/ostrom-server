// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.EndDeletedOrgSubscriptionUseCase
import dev.ostrom.server.kernel.module.OrgDeletionListener
import kotlin.uuid.Uuid

/**
 * Billing's part of an organization's deletion: the subscription ends at once. Nothing is erased at the purge: the
 * billing row and the invoices are accounting records, kept as the law asks.
 *
 * @param endSubscription Ends the subscription.
 */
class BillingOrgDeletionListener(private val endSubscription: EndDeletedOrgSubscriptionUseCase) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        endSubscription(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        // Kept: accounting records outlive the organization.
    }
}
