// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The event a wait for an event waits for, from a CI or a script (`POST /api/v1/waits/{id}/event`). */
interface ReceiveWaitEventUseCase {
    /**
     * Resolves the wait [id] names with what its caller sent. No sign-in: its secret is the proof.
     *
     * @param id The wait's id, as the path gives it.
     * @param secret The secret its caller presents.
     * @param body What it posted, at most [TimedWaitRules.MAX_EVENT_BYTES] and one byte read.
     *
     * @return The wait, resolved.
     *
     * @throws WaitEventRateLimitException Past 30 calls a minute to the address.
     * @throws WaitNotFoundException For an unknown wait, a wrong secret and a wait no longer open alike.
     * @throws WaitEventTooLargeException For a body larger than 16 KiB.
     * @throws InvalidWaitException For a body that is not an event.
     */
    suspend fun receive(id: String, secret: String?, body: ByteArray): Wait
}

/**
 * [ReceiveWaitEventUseCase] (TeamCode's `WaitService.receiveEvent`). The presented secret is hashed, then compared
 * with the stored hash in constant time; an unknown wait is compared with a fixed hash, so the work does not tell
 * them apart. The first event wins against a timeout and against another event.
 *
 * @param repository Where waits are stored.
 * @param secrets Hashes the presented secret.
 * @param limit The calls a minute per address.
 * @param events Told of the resolution, as of an answer.
 * @param clock The time.
 */
class ReceiveWaitEventUseCaseImpl(
    private val repository: TimedWaitsRepository,
    private val secrets: WaitEventSecrets,
    private val limit: WaitEventRateLimit,
    private val events: WaitEvents,
    private val clock: Clock,
) : ReceiveWaitEventUseCase {
    override suspend fun receive(id: String, secret: String?, body: ByteArray): Wait {
        limit.check(id.take(MAX_KEY))
        val waitId = Uuid.parseOrNull(id)
        val target = waitId?.let { repository.eventTarget(it) }
        // At most one byte more than a secret is hashed: a longer one matches nothing all the same.
        val presented = secrets.hash(secret.orEmpty().take(MAX_SECRET + 1))
        val matches = constantTimeEquals(presented, if (target == null) NO_HASH else target.secretHash)
        if (waitId == null || target == null || !matches) throw WaitNotFoundException()
        if (body.size > TimedWaitRules.MAX_EVENT_BYTES) throw WaitEventTooLargeException()
        val event = TimedWaitRules.event(body)
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val answer = WaitAnswer(TimedWaitRules.EVENT_RECEIVED, event.values, TimedWaitRules.SYSTEM, now)
        val resolved = repository.settle(target.orgId, waitId, answer, due = false) ?: throw WaitNotFoundException()
        events.resolved(resolved)
        return resolved
    }

    private companion object {
        const val MAX_KEY = 64
        const val MAX_SECRET = 200

        /** A hash no secret has (not hexadecimal): what an unknown wait is compared with. */
        const val NO_HASH = "----------------------------------------------------------------"
    }
}
