// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import run.ostrom.server.runners.domain.RunnerWorkSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [RunnerWorkSignals] in this instance's memory. An execution queued here wakes its organization's heartbeats at once;
 * one queued on another instance is seen by [watch], which looks once per [lookEvery] for each organization that has
 * heartbeats waiting here, never once per runner. A burst larger than the buffer drops the oldest wake-ups, which the
 * next look makes up for.
 *
 * @param assignments Whether an organization has an execution queued, or assigned and not handed over yet.
 * @param logger Where a failed look is reported.
 * @param lookEvery Between two looks at the database: the longest a session queued on another instance waits for
 *   its machine's heartbeat to look.
 * @param maxWaiting The heartbeats that may wait on this instance at once: each holds a request, never a database
 *   connection.
 */
class InProcessRunnerWorkSignals(
    private val assignments: RunnerAssignmentsRepository,
    private val logger: Logger,
    private val lookEvery: Duration = 1.seconds,
    private val maxWaiting: Int = MAX_WAITING,
) : RunnerWorkSignals {
    private val arrivals =
        MutableSharedFlow<Uuid>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val waiting = mutableMapOf<Uuid, Int>()

    override fun arrived(orgId: Uuid) {
        arrivals.tryEmit(orgId)
    }

    override suspend fun await(orgId: Uuid): Boolean {
        val entered = mutex.withLock {
            (waiting.values.sum() < maxWaiting).also { if (it) waiting[orgId] = (waiting[orgId] ?: 0) + 1 }
        }
        if (!entered) return false
        try {
            arrivals.first { it == orgId }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val left = waiting.getValue(orgId) - 1
                    if (left == 0) waiting.remove(orgId) else waiting[orgId] = left
                }
            }
        }
        return true
    }

    override suspend fun watch(): Nothing {
        while (true) {
            delay(lookEvery)
            look()
        }
    }

    /** One look at the database for each organization that has heartbeats waiting here. */
    internal suspend fun look() {
        for (orgId in mutex.withLock { waiting.keys.toList() }) {
            val found = try {
                assignments.hasWork(orgId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("runner work look failed", mapOf("error" to error::class.simpleName))
                false
            }
            if (found) arrived(orgId)
        }
    }

    /** Defaults of the enclosing type. */
    companion object {
        /** The heartbeats one instance holds at once by default. */
        const val MAX_WAITING = 1_000

        private const val BUFFER = 256
    }
}
