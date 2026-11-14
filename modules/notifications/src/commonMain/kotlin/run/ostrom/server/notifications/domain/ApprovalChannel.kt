// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.events.GateSummary
import kotlin.uuid.Uuid

/**
 * A way to tell an approver of a request for a yes besides the push and the e-mail of this module: the module that
 * offers it binds it in Koin (integrations-slack binds `slack`). Its deliveries go through the same queue as the push
 * and the e-mail; an approver is told on it while it reaches them and their `waiting` switch on it is on.
 */
interface ApprovalChannel {
    /** Stored with the deliveries; the same id as the module's [NotificationChannel], at most 16 characters. */
    val id: String

    /**
     * @param orgId The organization.
     * @param memberIds The approvers' memberships.
     *
     * @return Those of [memberIds] this channel can reach now, in one read whatever their number.
     */
    suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid>

    /**
     * Tells one approver of one request. Bounded by the caller's deadline; a failed read may throw (retried).
     *
     * @param delivery The delivery.
     * @param gate Its gate, as it is now.
     * @param link The console's page of the request, where the yes is given.
     *
     * @return What the attempt produced.
     */
    suspend fun send(delivery: ApprovalDelivery, gate: GateSummary, link: String): ChannelResult
}
