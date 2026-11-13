// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Makes a model call wait for its turn in the org's admission queue (TeamCode's `AwaitAdmissionUseCase`), shared by
 * every replica: while every shared account it may use is busy, or while its member runs as many calls as their limit
 * allows. Waiting holds no database connection, no account and no budget.
 */
interface AwaitAdmissionUseCase {
    /**
     * Waits until [request] may start, then plans it with [start]; a plan [InferencePlan.Busy] puts it back to wait,
     * at its place. Gives up after [MAX_WAIT] with 503 `queue_timeout`; a full queue answers 503 `queue_full` at once.
     *
     * @param request The call.
     * @param running Whether the call already runs (a later try of it, after an account refused it): it goes back to
     *   wait first, as it would at its place.
     * @param start Plans the call, once it may start.
     *
     * @return The plan, or [InferencePlan.Refused] with the queue's refusal.
     */
    suspend operator fun invoke(
        request: AdmissionRequest,
        running: Boolean,
        start: suspend () -> InferencePlan,
    ): InferencePlan

    /**
     * Runs [block], the whole call, renewing its place every [AdmissionQueueRepository.RUNNING_RENEWAL] (a long stream
     * keeps counting against its member's limit) and removing it once [block] ends, however it ends.
     */
    suspend fun <T> holding(request: AdmissionRequest, block: suspend () -> T): T

    /** The bounds of a wait. */
    companion object {
        /** How long a call waits at most. */
        val MAX_WAIT: Duration = 90.seconds

        /** When a call refused by the queue may try again. */
        val RETRY: Duration = 15.seconds

        /** The first polls. */
        val POLL: Duration = 500.milliseconds

        /** How long removing a call's place may take, once it is done: its lease frees it otherwise. */
        val RELEASE_TIMEOUT: Duration = 5.seconds

        private const val FAST_POLLS = 4
        private const val MEDIUM_POLLS = 10
        private const val MEDIUM_FACTOR = 2
        private const val SLOW_FACTOR = 4
        private const val JITTER_FIFTHS = 5

        /**
         * The pause after the [attempt]th poll: each poll is a transaction under the org's lock and every waiting call
         * polls, so a long wait polls less. [POLL] for the first, then 1 s, then 2 s (well within the waiting lease),
         * give or take a fifth so that waiting calls do not poll in step (TeamCode's `pollDelay`).
         */
        fun pollDelay(attempt: Int, random: Random = Random.Default): Duration {
            if (attempt < FAST_POLLS) return POLL
            val base = POLL * if (attempt < MEDIUM_POLLS) MEDIUM_FACTOR else SLOW_FACTOR
            val spread = base.inWholeMilliseconds / JITTER_FIFTHS
            return (base.inWholeMilliseconds - spread + random.nextLong(2 * spread + 1)).milliseconds
        }
    }
}

/**
 * [AwaitAdmissionUseCase] on the queue.
 *
 * @param queue The queue.
 * @param clock The time, which bounds the wait.
 * @param pause Waits between two polls ([delay]; tests move their clock).
 * @param random The jitter of the polls.
 */
class AwaitAdmissionUseCaseImpl(
    private val queue: AdmissionQueueRepository,
    private val clock: Clock,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : AwaitAdmissionUseCase {
    override suspend fun invoke(
        request: AdmissionRequest,
        running: Boolean,
        start: suspend () -> InferencePlan,
    ): InferencePlan {
        val deadline = clock.now() + AwaitAdmissionUseCase.MAX_WAIT
        if (running) queue.requeue(request.orgId, request.id)
        val wait = Wait(request, first = !running)
        var plan: InferencePlan? = null
        while (plan == null) {
            plan = wait.poll(start) ?: if (clock.now() >= deadline) {
                refusal(RefusalKind.QUEUE_TIMEOUT, timeoutMessage(wait.blocked, request), wait.model)
            } else {
                null
            }
            if (plan == null) pause(AwaitAdmissionUseCase.pollDelay(wait.attempt++, random))
        }
        return plan
    }

    /** One call's wait, over its polls. */
    private class Wait(val request: AdmissionRequest, var first: Boolean) {
        var attempt = 0

        /** The model of its last busy plan, for the record of a refusal. */
        var model = ""

        /** Whether its last poll found its member at their limit. */
        var blocked = false
    }

    /** One poll: the plan once the call may start, its refusal by the queue, or null while it waits. */
    private suspend fun Wait.poll(start: suspend () -> InferencePlan): InferencePlan? = try {
        val entry = queue.enter(request, first)
        first = false
        blocked = (entry as? AdmissionEntry.Waiting)?.blocked == true
        if (entry == AdmissionEntry.Admitted) started(start()) else null
    } catch (refused: QueueRefusedException) {
        refusal(refused.kind, queueMessage(refused.kind), model)
    }

    /** A busy plan puts the call back to wait, at its place. */
    private suspend fun Wait.started(plan: InferencePlan): InferencePlan? = if (plan is InferencePlan.Busy) {
        model = plan.model
        queue.requeue(request.orgId, request.id)
        null
    } else {
        plan
    }

    override suspend fun <T> holding(request: AdmissionRequest, block: suspend () -> T): T = coroutineScope {
        val renewal = launch {
            while (true) {
                delay(AdmissionQueueRepository.RUNNING_RENEWAL)
                quietly { queue.renew(request.orgId, request.id) }
            }
        }
        try {
            block()
        } finally {
            renewal.cancel()
            // A revoked or gone caller still removes its own place; the lease frees it if the database cannot.
            withContext(NonCancellable) {
                withTimeoutOrNull(AwaitAdmissionUseCase.RELEASE_TIMEOUT) {
                    quietly { queue.release(request.orgId, request.id) }
                }
            }
        }
    }

    private fun refusal(kind: RefusalKind, message: String, model: String) =
        InferencePlan.Refused(InferenceRefusal(kind, message, clock.now() + AwaitAdmissionUseCase.RETRY), model)

    private fun queueMessage(kind: RefusalKind): String = if (kind == RefusalKind.QUEUE_FULL) {
        "Ostrom's admission queue is full for you or your organization. Retry shortly."
    } else {
        "Your place in Ostrom's admission queue expired. Send the request again."
    }

    private fun timeoutMessage(blocked: Boolean, request: AdmissionRequest): String {
        val seconds = AwaitAdmissionUseCase.MAX_WAIT.inWholeSeconds
        val last = if (blocked) {
            "you already run ${request.limit} model calls at once, your limit"
        } else {
            "the shared accounts were busy or other calls went first"
        }
        return "Ostrom waited ${seconds}s for free capacity without your turn coming ($last). Retry shortly."
    }
}

/** Runs a write of the queue whose failure the lease repairs: never fails the call. */
private suspend fun quietly(write: suspend () -> Unit) {
    try {
        write()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Left to the lease.
    }
}
