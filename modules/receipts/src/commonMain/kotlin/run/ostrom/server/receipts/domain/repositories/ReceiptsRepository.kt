// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.domain.repositories

import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.StoredReceipt
import kotlin.uuid.Uuid

/** Storage of the signed receipts, isolated by organization. */
interface ReceiptsRepository {
    /**
     * Stores a receipt unless one with its id exists in its organization.
     *
     * @param receipt The signed receipt.
     *
     * @return The stored receipt: [receipt], or the one stored first under the same id.
     */
    suspend fun insertIfAbsent(receipt: StoredReceipt): StoredReceipt

    /**
     * Reads a receipt.
     *
     * @param orgId The organization.
     * @param id The receipt id.
     *
     * @return The receipt, or null when it is not one of the organization's.
     */
    suspend fun get(orgId: Uuid, id: String): StoredReceipt?

    /**
     * Lists receipts, newest first.
     *
     * @param orgId The organization.
     * @param query The filters and page.
     *
     * @return The receipts.
     */
    suspend fun list(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt>
}
