// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

/** One pass of the webhooks worker, on every replica at once. */
interface DeliverWebhooksUseCase {
    /**
     * Queues the new events of each organization with work, then attempts what is due.
     *
     * @return The deliveries attempted.
     */
    suspend operator fun invoke(): Int

    /**
     * Gives up on the deliveries pending too long and deletes the log past its retention.
     *
     * @return The rows changed.
     */
    suspend fun maintain(): Int
}
