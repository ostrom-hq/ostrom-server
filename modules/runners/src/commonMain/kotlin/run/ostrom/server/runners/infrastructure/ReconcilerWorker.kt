// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The leased reconciler loop: claims due sessions (at most [batch] per pass, with a [lease]) and runs one
 * reconciliation step on each. Any replica may run it; the lease keeps one replica per session.
 *
 * @param repository The sessions.
 * @param reconcile One step.
 * @param logger Where failed steps are reported, without the cluster's raw error body.
 * @param clock The time.
 * @param owner This replica's lease owner id.
 */
@Suppress("LongParameterList") // Four collaborators and four tunings with defaults, all named at the call sites.
class ReconcilerWorker(
    private val repository: CloudSessionsRepository,
    private val reconcile: ReconcileCloudSessionUseCase,
    private val logger: Logger,
    private val clock: Clock,
    private val owner: String = "replica-" + Uuid.random().toHexString(),
    private val lease: Duration = DEFAULT_LEASE,
    private val batch: Int = DEFAULT_BATCH,
    private val idleDelay: Duration = DEFAULT_IDLE_DELAY,
) {
    /** Runs passes until the calling coroutine is cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (pass() == 0) delay(idleDelay)
        }
    }

    /**
     * One pass: claims due sessions and reconciles each.
     *
     * @return How many sessions were claimed.
     */
    suspend fun pass(): Int {
        val now = clock.now()
        val claimed = repository.claimDue(owner, now, now + lease, batch)
        for (session in claimed) {
            val next = try {
                reconcile(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    "cloud session step failed",
                    mapOf(
                        "session_id" to session.id,
                        "state" to session.state.wire,
                        "error" to error::class.simpleName,
                    ),
                )
                clock.now() + RETRY_DELAY
            }
            repository.release(session.id, owner, next)
        }
        return claimed.size
    }

    private companion object {
        private val RETRY_DELAY = 30.seconds

        /** How long a claimed session stays this replica's. */
        private val DEFAULT_LEASE = 60.seconds

        /** Sessions claimed per pass. */
        const val DEFAULT_BATCH = 8

        /** The pause between two passes that found nothing to do. */
        private val DEFAULT_IDLE_DELAY = 1.seconds
    }
}
