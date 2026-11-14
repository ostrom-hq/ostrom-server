// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * How long a paused session keeps its workspace, as TeamCode's `pausedRetentionDays`: read when the session pauses, so
 * that a later change of the setting applies to sessions paused afterwards only.
 */
fun interface PausedRetention {
    /**
     * The retention in force.
     *
     * @return The days a paused session keeps its workspace before it is terminated with it; null keeps it.
     */
    suspend fun days(): Int?
}

/** Paused sessions are kept, as long as no module sets a retention (the cloud module does). */
object KeepPausedSessions : PausedRetention {
    override suspend fun days(): Int? = null
}
