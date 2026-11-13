// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.receipts.domain.services.ReceiptOwners
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlin.uuid.Uuid

/**
 * [ReceiptOwners] on the segments: a receipt about a session belongs to the session's person (TeamCode #238).
 *
 * @param repository The sessions.
 */
class SessionsReceiptOwners(private val repository: SessionsRepository) : ReceiptOwners {
    override suspend fun ownerOf(orgId: Uuid, sessionId: String): String? = repository.get(orgId, sessionId)?.dev
}
