// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/** "Send a test to my phone" (`POST /api/v1/notifications/push/test`, TeamCode's `PushChannel.test`). */
fun interface SendTestPushUseCase {
    /**
     * Sends a `test` notification to the member's phones now, at most once every ten seconds per member on this
     * replica.
     *
     * @param orgId The organization.
     * @param memberId The member, who is the caller.
     *
     * @return Each phone's result.
     *
     * @throws PushTestRateLimitedException Within ten seconds of the member's previous test.
     * @throws PushOffException While no provider is chosen.
     * @throws NoPushPhoneException When the member has no phone push can reach.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): List<PushResult>
}
