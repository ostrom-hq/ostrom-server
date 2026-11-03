// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.engine

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** One pass of an engine replica over the runs due now, of every organization. */
interface TickRunsUseCase {
    /**
     * Leases the runs due now, plays each one, then releases it with when it is due again.
     *
     * @return How many runs were leased.
     */
    suspend fun tick(): Int
}

/**
 * [TickRunsUseCase] on the repository: a run is leased to one replica at a time, for [lease] at most, so a replica
 * that stops leaves its runs to the others once the lease lapses; nothing it wrote is played again (a visit's key is
 * written once).
 *
 * @param repository The runs.
 * @param play Plays one run.
 * @param clock The clock.
 * @param logger Where a run that could not be played is reported (its error's type only).
 * @param owner This replica.
 * @param batch The most runs a pass leases.
 * @param lease How long a lease holds.
 */
@Suppress("LongParameterList") // The replica's identity and bounds have defaults; tests set them.
class TickRunsUseCaseImpl(
    private val repository: RunEngineRepository,
    private val play: PlayRunUseCase,
    private val clock: Clock,
    private val logger: Logger,
    private val owner: String = "engine-" + Uuid.random().toHexString(),
    private val batch: Int = BATCH,
    private val lease: Duration = LEASE,
) : TickRunsUseCase {
    override suspend fun tick(): Int {
        val now = clock.now()
        val due = repository.claimDue(owner, now, now + lease, batch)
        for (ref in due) {
            // A run that fails to play (the database, a module) is tried again later; the others go on.
            val next = try {
                play.play(ref, owner)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("workflow run step failed", mapOf("error" to error::class.simpleName))
                clock.now() + RETRY
            }
            repository.release(ref.orgId, ref.id, owner, next)
        }
        return due.size
    }

    /** The bounds of a pass. */
    companion object {
        /** The most runs a pass leases. */
        const val BATCH: Int = 50

        /** How long a lease holds: far longer than a play, which never waits. */
        val LEASE: Duration = 60.seconds

        /** When a run that failed to play is tried again. */
        val RETRY: Duration = 30.seconds
    }
}
