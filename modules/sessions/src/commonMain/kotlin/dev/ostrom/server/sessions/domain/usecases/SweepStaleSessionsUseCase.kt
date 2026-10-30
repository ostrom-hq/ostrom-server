package dev.ostrom.server.sessions.domain.usecases

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.repositories.StaleSession
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Closes the sessions whose launcher stopped giving signs of life (CORRECTIFS-2): their receipt is signed with the
 * source `launcher lost: …`, instead of staying open forever. Only sessions that gave a sign of life once are followed
 * (a hook without the launcher never does).
 */
interface SweepStaleSessionsUseCase {
    /**
     * Closes the stale sessions of every organization.
     *
     * @return The segments closed.
     */
    suspend operator fun invoke(): List<String>
}

/**
 * [SweepStaleSessionsUseCase] on the repository and [CloseSessionUseCase].
 *
 * @param repository The sessions.
 * @param close Ends and signs.
 * @param clock The time.
 * @param logger Says how many were closed.
 * @param staleAfter How long without a sign of life (120 s in the prototype; the launcher sends one every 20 s).
 */
class SweepStaleSessionsUseCaseImpl(
    private val repository: SessionsRepository,
    private val close: CloseSessionUseCase,
    private val clock: Clock,
    private val logger: Logger,
    private val staleAfter: Duration = STALE_AFTER,
) : SweepStaleSessionsUseCase {
    override suspend fun invoke(): List<String> {
        val stale = repository.stale(clock.now() - staleAfter, BATCH)
        val closed = stale.mapNotNull { closeLost(it) }
        if (closed.isNotEmpty()) {
            logger.warn("sessions closed, their launcher stopped answering", mapOf("count" to closed.size))
        }
        return closed
    }

    // One session that fails to close never holds back the others: it is retried at the next run.
    private suspend fun closeLost(session: StaleSession): String? = try {
        val source = "${Session.LOST}: no sign of life since ${session.lastSeenAt.isoMillis()}"
        close(session.orgId, session.id, EndSession(source = source)).id
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("lost session not closed", mapOf("session_id" to session.id), error)
        null
    }

    /** The sweep's settings. */
    companion object {
        /** How long a launcher may stay silent. */
        val STALE_AFTER: Duration = 120.seconds

        /** How often the sweep runs. */
        val EVERY: Duration = 30.seconds

        /** At most this many sessions closed per run; the next run takes the rest. */
        const val BATCH: Int = 100
    }
}
