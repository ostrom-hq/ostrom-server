// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.services

import kotlin.uuid.Uuid

/**
 * What a delivery by session reads of a step's cloud session besides its run: whose it is, and whether its agent
 * asked a person.
 */
interface StepSessions {
    /**
     * Whether a cloud session is a person's.
     *
     * @param orgId The organization.
     * @param sessionId The cloud session.
     * @param login The person's login.
     *
     * @return True when it exists and they own it.
     */
    suspend fun ownedBy(orgId: Uuid, sessionId: Uuid, login: String): Boolean

    /**
     * Whether the agent of a cloud session asked something that is still open: a wait of any of the sessions its
     * launches opened (any kind of wait counts as having asked).
     *
     * @param orgId The organization.
     * @param sessionId The cloud session.
     *
     * @return True when one is open.
     */
    suspend fun asked(orgId: Uuid, sessionId: Uuid): Boolean
}
