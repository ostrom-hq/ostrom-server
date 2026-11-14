// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SendTestPushUseCase] through the push channel, with the rate of TeamCode's test: one per member every ten seconds
 * on each replica, kept in memory (a test costs one push per phone, nothing is stored).
 *
 * @param channel The push channel, built at the first test: the routes need nothing of it until then.
 * @param clock The time.
 */
class SendTestPushUseCaseImpl(channel: () -> PushChannel, private val clock: Clock) : SendTestPushUseCase {
    private val push by lazy(channel)
    private val mutex = Mutex()
    private val last = HashMap<Pair<Uuid, Uuid>, Instant>()

    override suspend fun invoke(orgId: Uuid, memberId: Uuid): List<PushResult> {
        val now = clock.now()
        mutex.withLock {
            // The tests past their window are forgotten, so the map holds only the last ten seconds.
            last.entries.removeAll { now - it.value >= TEST_WINDOW }
            if (last.containsKey(orgId to memberId)) throw PushTestRateLimitedException()
            last[orgId to memberId] = now
        }
        return push.test(orgId, memberId)
    }
}

/** The shortest time between two test pushes of a member, and the `Retry-After` of a refusal. */
val TEST_WINDOW: Duration = 10.seconds
