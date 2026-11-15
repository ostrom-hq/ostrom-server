// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudConversationService
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudFileService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * TeamCode's conversation sweeps, on the instance's scheduled work: the transcript retention (the cloud runtime's
 * `transcriptRetentionDays`, kept forever when null or without the cloud module), the inputs of sessions that ended,
 * the messages still waiting to wake their session, and the files sent to sessions (`runners.cloud.files.v1`: expired
 * or released files' bytes, then their rows, and the past days' counters).
 *
 * @param store The transcript and inputs.
 * @param conversation Wakes the sessions.
 * @param runtime The instance's cloud runtime; null without the cloud module.
 * @param clock The time.
 * @param files The files sent to sessions; null when this server takes none.
 */
class CloudConversationMaintenance(
    private val store: CloudConversationStore,
    private val conversation: CloudConversationService,
    private val runtime: CloudRuntimeSource?,
    private val clock: Clock,
    private val files: CloudFileService? = null,
) {
    /** One pass of each sweep, each bounded. */
    suspend fun run() {
        val now = clock.now()
        val days = if (runtime == null) null else runtime.current().transcriptRetentionDays
        if (days != null) store.purge(now - days.days, BATCH)
        store.cancelEnded(BATCH, now)
        conversation.wakePending(BATCH)
        files?.sweep()
    }

    /**
     * Runs the sweeps every minute, under the instance's lease; a failed pass is logged and the next one runs.
     *
     * @param work The instance's scheduled work.
     * @param logger Where a failed pass goes.
     */
    suspend fun repeat(work: ScheduledWork, logger: Logger): Nothing =
        work.repeatDue(JOB, logger, "conversation maintenance failed") { run() }

    /** The job and its bounds. */
    companion object {
        /** Rows or sessions one sweep handles. */
        const val BATCH: Int = 500

        /** Every minute, each pass within two. */
        val JOB: ScheduledJob = ScheduledJob("runners/conversation", 1.minutes, 2.minutes)
    }
}
