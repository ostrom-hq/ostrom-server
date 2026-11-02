// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.domain

/** The monthly budget that applies to a viewer. */
interface SpendingBudgets {
    /**
     * Reads it.
     *
     * @param viewer The organization's budget for whoever sees all of it, the member's own otherwise.
     * @return The limit in millionths of a US dollar, or null when there is none.
     */
    suspend fun monthlyMicros(viewer: Viewer): Long?
}
