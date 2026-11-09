// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel

/**
 * A confirmed write's secret is shown once, to the person who asked (decision of 03/11/2026, "shown once"), as the
 * console shows an invitation link once: the owning module says what its result is without the secret
 * ([run.ostrom.server.kernel.auth.GovernanceChange.shownOnce]). The first read by the person who asked, with a token
 * (the CLI that waits for it), gets the result as written, and the result without its secret then replaces it. A
 * browser session never reads the secret, theirs included (it would burn the CLI's read), nor does anyone else.
 *
 * @param changes The modules' writes.
 * @param repository The confirmations.
 */
class ConfirmationSecrets(private val changes: GovernanceChanges, private val repository: ConfirmationsRepository) {
    /**
     * The confirmation as [viewerId] may read it.
     *
     * @param confirmation The confirmation, as stored.
     * @param viewer Who reads it.
     *
     * @return The confirmation, its result's secret removed unless [viewer] asked for it, reads with a token and has
     *   not read it yet.
     */
    suspend fun shownTo(confirmation: Confirmation, viewer: AuthenticatedPrincipal): Confirmation {
        val result = confirmation.result
        val kind = confirmation.write.kind
        // A kind no module of this server owns any more is read as stored: nothing knows its secret.
        val hidden = result?.let { changes.ownerOf(kind)?.shownOnce(kind, it) }
        val asker = confirmation.requestedBy.userId == viewer.subjectId && viewer.channel == PrincipalChannel.API_TOKEN
        return when {
            hidden == null || hidden == result -> confirmation
            !asker -> confirmation.copy(result = hidden)
            else -> confirmation.also { repository.settle(it.orgId, it.id, hidden, null) }
        }
    }
}
