// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CollectCloudGarbageUseCase
import run.ostrom.server.runners.domain.GarbageClusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The garbage collection of the clusters (TeamCode's scheduled cloud garbage collection): every [INTERVAL], on one
 * replica at a time ([ScheduledWork]'s `runners/cloud-garbage` lease), each cluster is collected; one that does not
 * open or fails is reported (its type only) and the others go on.
 *
 * @param work The lease of the periodic jobs.
 * @param clusters The clusters.
 * @param collect One cluster's collection.
 * @param logger Where a failure is reported.
 */
class CloudGarbageWorker(
    private val work: ScheduledWork,
    private val clusters: GarbageClusters,
    private val collect: CollectCloudGarbageUseCase,
    private val logger: Logger,
) {
    /** Asks the lease every [TICK] until the calling coroutine is cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            pass()
            delay(TICK)
        }
    }

    /** One pass, if it is due and this replica holds the lease. */
    suspend fun pass() {
        try {
            work.runDue(JOB, INTERVAL, TIMEOUT) { collectAll() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("cloud garbage collection failed", mapOf("error" to error::class.simpleName))
        }
    }

    private suspend fun collectAll() {
        for (cluster in clusters.clusters()) {
            try {
                val report = collect(cluster)
                if (report.skippedNamespaces > 0) {
                    logger.warn(
                        "cloud garbage collection skipped namespaces of a cluster",
                        mapOf("cluster_id" to cluster.id, "namespaces" to report.skippedNamespaces),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    "cloud garbage collection failed for a cluster",
                    mapOf("cluster_id" to cluster.id, "error" to error::class.simpleName),
                )
            }
        }
    }

    private companion object {
        const val JOB = "runners/cloud-garbage"

        /** TeamCode's: every 10 minutes. */
        private val INTERVAL: Duration = 10.minutes

        /** The longest a pass may take (the lease's bound is 5 minutes). */
        private val TIMEOUT: Duration = 4.minutes

        /** How often a replica asks whether the pass is due. */
        private val TICK: Duration = 1.minutes
    }
}
