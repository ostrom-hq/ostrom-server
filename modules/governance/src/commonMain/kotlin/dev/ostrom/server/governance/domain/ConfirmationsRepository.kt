// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The confirmations, each within its organization. */
interface ConfirmationsRepository {
    /**
     * Stores a new confirmation.
     *
     * @param confirmation The confirmation.
     */
    suspend fun insert(confirmation: Confirmation)

    /**
     * Reads one.
     *
     * @param orgId The organization.
     * @param id The id.
     *
     * @return The confirmation, or null when the organization has none with this id.
     */
    suspend fun get(orgId: Uuid, id: String): Confirmation?

    /**
     * The confirmations a person still waits for.
     *
     * @param orgId The organization.
     * @param userId The person.
     * @param now The current time: those past their expiry are left out.
     *
     * @return The pending confirmations, oldest first.
     */
    suspend fun waiting(orgId: Uuid, userId: String, now: Instant): List<Confirmation>

    /**
     * Marks the pending confirmations past their expiry as expired.
     *
     * @param orgId The organization.
     * @param now The current time.
     *
     * @return Those it marked, each once whichever instance marks them.
     */
    suspend fun expire(orgId: Uuid, now: Instant): List<Confirmation>

    /**
     * Answers a confirmation still pending at the outcome's time, once: of two answers, only one changes it.
     *
     * @param orgId The organization.
     * @param id The id.
     * @param outcome The answer.
     *
     * @return True when this answer changed it.
     */
    suspend fun answer(orgId: Uuid, id: String, outcome: ConfirmationOutcome): Boolean

    /**
     * Records the outcome of a confirmed write: its result, or why it failed.
     *
     * @param orgId The organization.
     * @param id The id.
     * @param result What the write returned.
     * @param error Why it was not written; the confirmation is then failed.
     */
    suspend fun settle(orgId: Uuid, id: String, result: JsonElement?, error: ConfirmationError?)

    /**
     * The organizations that have pending confirmations past their expiry, across organizations (a function owned by
     * `ostrom_router`, docs/tenancy.md).
     *
     * @param now The current time.
     * @param limit How many at most.
     *
     * @return The organizations.
     */
    suspend fun organizationsWithExpired(now: Instant, limit: Int): List<Uuid>
}
