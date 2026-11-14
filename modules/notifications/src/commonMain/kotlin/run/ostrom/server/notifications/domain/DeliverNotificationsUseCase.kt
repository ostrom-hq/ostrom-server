// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

/**
 * The notification router's worker (TeamCode's `DeliverNotificationsUseCase`, docs/notifications.md "Delivery"): a
 * pass turns the journal's new events into deliveries, then attempts what is due. One replica at a time runs it
 * (`notifications/deliver`); a crash between the halves loses nothing, as deliveries and the cursor are written
 * together and a claimed delivery comes back after its lease.
 */
interface DeliverNotificationsUseCase {
    /**
     * One pass: [fanOut], then the attempts.
     *
     * @return The deliveries attempted.
     */
    suspend operator fun invoke(): Int

    /**
     * Turns the journal's new events into deliveries, organization by organization, a page at a time.
     *
     * @return The deliveries queued.
     */
    suspend fun fanOut(): Int

    /**
     * Gives up the deliveries pending for more than an hour, deletes the finished ones older than a day
     * (`notifications/maintain`).
     *
     * @return The rows given up and deleted.
     */
    suspend fun maintain(): Long
}
