// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the waits of a session have to say to its Stop hook.
 *
 * @property open A wait of the session is still open: the hook keeps holding the agent.
 * @property messages The answers that arrived, oldest first, each handed over once.
 */
data class WaitWakeResult(val open: Boolean, val messages: List<String>)

/**
 * Waking an agent's session with the answers to its waits. An `ostrom <agent>` launch runs in a terminal, which has no
 * queue of messages to read: its Stop hook holds the agent while a wait of the session is open and asks here; each
 * answer is handed over once, and the agent continues with it as its next message (TeamCode's local run,
 * `docs/waits.md` « A local run »). An answer not taken (the terminal closed) stays on the wait.
 */
interface WakeWaitsUseCase {
    /**
     * Waits up to [waitMillis] for an answer to a wait of the agent's session.
     *
     * @param caller An agent token, in its session.
     * @param waitMillis How long the call may be held, 0 to 30 000; 25 000 when null.
     *
     * @return The answers, and whether a wait is still open; at once when none is open.
     *
     * @throws WaitForbiddenException When the caller is not an agent token in a session.
     * @throws InvalidWaitException When [waitMillis] is out of bounds.
     */
    suspend fun wake(caller: WaitCaller, waitMillis: Long?): WaitWakeResult
}

/**
 * [WakeWaitsUseCase]: looks every second, two queries a look (the answers to take, then whether one is still open),
 * through the partial index of the answers not handed over and the session index.
 *
 * @param repository Where waits are stored.
 * @param sessions Who answered, by username.
 * @param clock The time.
 * @param sleep Waits between two looks.
 */
class WakeWaitsUseCaseImpl(
    private val repository: WaitsRepository,
    private val sessions: WaitSessions,
    private val clock: Clock,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : WakeWaitsUseCase {
    override suspend fun wake(caller: WaitCaller, waitMillis: Long?): WaitWakeResult {
        val segments = caller.segments
        if (!caller.agent || segments == null) throw WaitForbiddenException("Only an agent's launch wakes its session.")
        val hold = waitMillis ?: DEFAULT_MILLIS
        requireWait(hold in 0..MAX_MILLIS) { "waitMillis is 0 to $MAX_MILLIS." }
        val deadline = clock.now() + hold.milliseconds
        while (true) {
            val taken = repository.takeAnswered(caller.orgId, segments, BATCH)
            val open = repository.hasOpen(caller.orgId, segments)
            // An answer and its wake are written at once: no answer to take and none open means nothing will come.
            if (taken.isNotEmpty() || !open || clock.now() >= deadline) {
                return WaitWakeResult(open, taken.map { WaitMessages.answered(it, username(caller, it)) })
            }
            sleep(POLL_MILLIS)
        }
    }

    private suspend fun username(caller: WaitCaller, wait: Wait): String? =
        wait.answer?.let { sessions.usernameOf(caller.orgId, it.author) }

    /** The bounds of a wake. */
    companion object {
        /** The longest a wake is held. */
        const val MAX_MILLIS = 30_000L

        /** How long a wake is held when its caller does not say. */
        const val DEFAULT_MILLIS = 25_000L

        /** Between two looks. */
        const val POLL_MILLIS = 1_000L

        /** The most answers handed over at once. */
        const val BATCH = 25
    }
}
