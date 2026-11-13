// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.domain

/** Records each step of a confirmation in its organization's signed audit log. */
interface ConfirmationAudit {
    /**
     * Records one step.
     *
     * @param confirmation The confirmation, as it stands after the step.
     * @param step The step.
     * @param actorUserId Who made it: the requester, the person who answered, or null for the server (an expiry).
     */
    suspend fun record(confirmation: Confirmation, step: ConfirmationStep, actorUserId: String?)
}
