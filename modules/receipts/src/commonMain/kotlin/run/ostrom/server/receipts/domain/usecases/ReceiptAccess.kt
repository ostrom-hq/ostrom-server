// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.domain.usecases

import run.ostrom.server.receipts.domain.models.ReceiptAudience
import run.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.services.ReceiptReaders

/**
 * Who reads a receipt (TeamCode #238, a session is its member's): an owner or an administrator of its organization,
 * the person of its session, and the approvers of that session's gates; a receipt about no session (a harness, a
 * decision, a gate of a pull request's CI) is the organization's, read by any of its readers. Anyone else is answered
 * as if the receipt did not exist.
 *
 * @param repository The receipts.
 * @param readers Who approves a session's gates.
 */
class ReceiptAccess(private val repository: ReceiptsRepository, private val readers: ReceiptReaders) {
    /**
     * Whether [reader] reads [receipt].
     *
     * @param reader The caller.
     * @param receipt A receipt of the caller's organization.
     *
     * @return True when they do.
     */
    suspend fun canRead(reader: ReceiptReader, receipt: StoredReceipt): Boolean {
        val session = receipt.sessionId
        val person = reader.person
        return when {
            reader.readsAll || session == null -> true
            person == null -> false
            receipt.member == person -> true
            else -> readers.approves(reader.orgId, person, session)
        }
    }

    /**
     * Reads a receipt the caller may read.
     *
     * @param reader The caller.
     * @param id The receipt.
     *
     * @return The receipt.
     *
     * @throws ReceiptNotFoundException When it is not one of the organization's, or not one the caller reads.
     */
    suspend fun read(reader: ReceiptReader, id: String): StoredReceipt =
        repository.get(reader.orgId, id)?.takeIf { canRead(reader, it) } ?: throw ReceiptNotFoundException()

    /**
     * The receipts a list keeps for [reader].
     *
     * @param reader The caller.
     *
     * @return Null for an owner or an administrator (every receipt), the caller's audience otherwise.
     */
    suspend fun audience(reader: ReceiptReader): ReceiptAudience? {
        if (reader.readsAll) return null
        val person = reader.person ?: return ReceiptAudience(null, emptyList())
        return ReceiptAudience(person, readers.approvedSessions(reader.orgId, person))
    }
}
