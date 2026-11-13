// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.domain.services

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.receipts.domain.models.ReceiptReader
import kotlin.uuid.Uuid

/**
 * Who reads the receipts of a session beside its person and the administrators: the approvers of its gates (the gates
 * module binds the real one; until it does, a person is resolved by their organization alone and approves nothing).
 */
interface ReceiptReaders {
    /**
     * Resolves a person's credential.
     *
     * @param principal The authenticated person.
     *
     * @return The reader, or null when the credential acts in no organization.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal): ReceiptReader?

    /**
     * Whether [person] is an approver of a gate of [sessionId].
     *
     * @param orgId The organization.
     * @param person The person's login.
     * @param sessionId The session.
     *
     * @return True when they are.
     */
    suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean

    /**
     * The sessions whose gates [person] approves.
     *
     * @param orgId The organization.
     * @param person The person's login.
     *
     * @return The sessions, each once.
     */
    suspend fun approvedSessions(orgId: Uuid, person: String): List<String>
}

/** The person of a session, written with its receipts: the sessions module binds the real one. */
interface ReceiptOwners {
    /**
     * Reads the person of a session.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     *
     * @return Their login, or null when the session is unknown.
     */
    suspend fun ownerOf(orgId: Uuid, sessionId: String): String?
}
