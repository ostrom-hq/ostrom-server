// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.sessions.SessionEndListener
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Ending a session ends what waits on it: its open waits are cancelled (no answer, `resolvedAt` set) and each is
 * announced as cancelled, so "Waiting for you" empties without anyone dismissing them. Answered waits stay as they
 * were.
 */
interface CancelSessionWaitsUseCase : SessionEndListener

/**
 * [CancelSessionWaitsUseCase].
 *
 * @param repository Where waits are stored.
 * @param events Told of each cancellation.
 * @param clock The time.
 */
class CancelSessionWaitsUseCaseImpl(
    private val repository: WaitsRepository,
    private val events: WaitEvents,
    private val clock: Clock,
) : CancelSessionWaitsUseCase {
    override suspend fun ended(orgId: Uuid, segments: List<String>) {
        if (segments.isEmpty()) return
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        repository.cancelOpenOfSessions(orgId, segments, now).forEach { events.cancelled(it) }
    }
}
