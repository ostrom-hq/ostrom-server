// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.events

import kotlin.uuid.Uuid

/**
 * What a reader of the journal needs of a gate its approval events name: `cloud.approval.requested` carries
 * identifiers and the approver's login only, and the gate may have been answered since.
 *
 * @property id The gate's id.
 * @property open Whether it still waits for a yes or a no.
 * @property askedBy Who asked for the action, by login: none of them is asked while another approver may answer.
 * @property label The action, as the asker named it.
 * @property repo The repository it is about, if any.
 * @property pendingApprovers The logins whose request still waits for its answer, in the approvers' order.
 */
data class GateSummary(
    val id: String,
    val open: Boolean,
    val askedBy: List<String>,
    val label: String,
    val repo: String?,
    val pendingApprovers: List<String>,
)

/**
 * The organization's gates, read without the gates module's tables. The gates module binds it, so the notification
 * router tells an approver of a request, and stops once the gate is decided. Unbound, no gate is known.
 */
fun interface GateSummaries {
    /**
     * @param orgId The organization.
     * @param ids The gates' ids, as the journal names them.
     *
     * @return The gates that exist, in any order.
     */
    suspend fun of(orgId: Uuid, ids: Set<String>): List<GateSummary>
}
