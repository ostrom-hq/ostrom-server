// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.runners.domain.CloudConversationService
import run.ostrom.server.runners.domain.CloudConversationStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * TeamCode's conversation sweeps, on the instance's scheduled work: the transcript retention (the cloud runtime's
 * `transcriptRetentionDays`, kept forever when null or without the cloud module), the inputs of sessions that ended,
 * and the messages still waiting to wake their session.
 *
 * @param store The transcript and inputs.
 * @param conversation Wakes the sessions.
 * @param runtime The instance's cloud runtime; null without the cloud module.
 * @param clock The time.
 */
class CloudConversationMaintenance(
    private val store: CloudConversationStore,
    private val conversation: CloudConversationService,
    private val runtime: CloudRuntimeSource?,
    private val clock: Clock,
) {
    /** One pass of each sweep, each bounded. */
    suspend fun run() {
        val now = clock.now()
        runtime?.current()?.transcriptRetentionDays?.let { days -> store.purge(now - days.days, BATCH) }
        store.cancelEnded(BATCH, now)
        conversation.wakePending(BATCH)
    }

    /** The job and its bounds. */
    companion object {
        /** Rows or sessions one sweep handles. */
        const val BATCH: Int = 500

        /** Every minute, each pass within two. */
        val JOB: ScheduledJob = ScheduledJob("runners/conversation", 1.minutes, 2.minutes)
    }
}
