// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlin.time.Clock
import kotlin.time.Instant

/** The clock's side of the waits that resolve by themselves: a time that came, an event that never arrived. */
interface ResolveDueWaitsUseCase {
    /**
     * One pass: resolves the open waits for a time or an event whose instant has come, of every organization, at most
     * [BATCH], and marks each for its session's wake.
     *
     * @return How many it resolved; another replica's pass may have resolved the others.
     */
    suspend fun pass(): Int

    /** The bounds of a pass. */
    companion object {
        /** The most waits one pass resolves. */
        const val BATCH = 50
    }
}

/**
 * [ResolveDueWaitsUseCase] (TeamCode's `WaitService.resolveDue`). Each wait is one conditional update on its being
 * open and due, so replicas that pass together, or an event and its timeout, resolve it once; the wake reads the
 * answer the update wrote (`wake_pending`), so nothing is owed between the two.
 *
 * @param repository Where waits are stored.
 * @param events Told of each resolution, as of an answer.
 * @param clock The time.
 */
class ResolveDueWaitsUseCaseImpl(
    private val repository: TimedWaitsRepository,
    private val events: WaitEvents,
    private val clock: Clock,
) : ResolveDueWaitsUseCase {
    override suspend fun pass(): Int {
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        return repository.due(now, ResolveDueWaitsUseCase.BATCH).count { due ->
            val timedOut = due.resolver == WaitResolver.EVENT
            val action = if (timedOut) TimedWaitRules.TIMED_OUT else TimedWaitRules.TIME_REACHED
            val answer = WaitAnswer(action, emptyMap(), TimedWaitRules.SYSTEM, now)
            val resolved = repository.settle(due.orgId, due.id, answer, due = true)
            resolved?.also { events.resolved(it) } != null
        }
    }
}
