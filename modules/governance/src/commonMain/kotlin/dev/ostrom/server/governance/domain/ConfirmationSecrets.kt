// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

/**
 * A confirmed write's secret is shown once, to the person who asked (decision of 03/11/2026, "shown once"), as the
 * console shows an invitation link once: the owning module says what its result is without the secret
 * ([dev.ostrom.server.kernel.auth.GovernanceChange.shownOnce]). The first read by the person who asked gets the
 * result as written, and the result without its secret then replaces it; an owner or an admin who is not that person
 * only ever reads the result without its secret.
 *
 * @param changes The modules' writes.
 * @param repository The confirmations.
 */
class ConfirmationSecrets(private val changes: GovernanceChanges, private val repository: ConfirmationsRepository) {
    /**
     * The confirmation as [viewerId] may read it.
     *
     * @param confirmation The confirmation, as stored.
     * @param viewerId The person who reads it.
     *
     * @return The confirmation, its result's secret removed unless [viewerId] asked for it and has not read it yet.
     */
    suspend fun shownTo(confirmation: Confirmation, viewerId: String): Confirmation {
        val result = confirmation.result
        val kind = confirmation.write.kind
        val hidden = result?.let { changes.of(kind).shownOnce(kind, it) }
        return when {
            hidden == null || hidden == result -> confirmation
            confirmation.requestedBy.userId != viewerId -> confirmation.copy(result = hidden)
            else -> confirmation.also { repository.settle(it.orgId, it.id, hidden, null) }
        }
    }
}
