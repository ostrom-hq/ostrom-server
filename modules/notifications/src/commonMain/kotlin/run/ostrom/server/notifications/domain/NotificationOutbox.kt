// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.notifications.domain

import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A piece of work of the outbox about one gate: finding who to tell ([Plan]), the push to one phone, or the e-mail to
 * one approver.
 */
sealed interface Notice {
    /** The gate it is about, in the logs. */
    val gateId: Uuid

    /** `plan`, `push` or `email`, in the logs. */
    val kind: String

    /**
     * Does it: sends it through its provider, or works out the notices it stands for.
     *
     * @param push The push service.
     * @param mail The mail provider.
     *
     * @return The notices to queue next.
     */
    suspend fun deliver(push: PushSender, mail: MailSender): List<Notice>

    /**
     * Who to tell of a gate and how, worked out apart from the request (database reads): its notices are queued next.
     *
     * @property gateId The gate.
     * @property plan Reads the approvers, their phones and addresses, and returns the notices.
     */
    class Plan(override val gateId: Uuid, private val plan: suspend () -> List<Notice>) : Notice {
        override val kind: String get() = "plan"

        override suspend fun deliver(push: PushSender, mail: MailSender): List<Notice> = plan()
    }

    /**
     * The push to a phone.
     *
     * @property gateId The gate.
     * @property messages The message (one per phone: a refused phone is tried again alone).
     */
    data class Push(override val gateId: Uuid, val messages: List<PushMessage>) : Notice {
        override val kind: String get() = "push"

        override suspend fun deliver(push: PushSender, mail: MailSender): List<Notice> {
            push.send(messages)
            return emptyList()
        }
    }

    /**
     * The e-mail to an approver.
     *
     * @property gateId The gate.
     * @property mail The e-mail.
     */
    data class Email(override val gateId: Uuid, val mail: Mail) : Notice {
        override val kind: String get() = "email"

        override suspend fun deliver(push: PushSender, mail: MailSender): List<Notice> {
            mail.send(this.mail)
            return emptyList()
        }
    }
}

/**
 * Sends the notifications apart from the request that causes them: [offer] queues and returns at once, so a slow or
 * failing push service or mail provider never holds a request for a yes, which is stored and waits in the console and
 * the app's queue anyway. A notice that fails is logged (its kind, gate and attempt, never a token or an address) and
 * tried again after each of [retryDelays], then given up. Several workers send at once, so a slow provider does not
 * hold the others' notices. Once a gate is decided ([close]), nothing more is sent about it, retries included. The
 * queue lives in the server's memory: a restart loses the notices not sent yet, never a request.
 *
 * @param push The push service.
 * @param mail The mail provider.
 * @param logger Logs each failure and each notice given up.
 * @param retryDelays The pause before each new attempt; their count is the number of retries.
 * @param capacity The notices queued at most; one more is dropped and logged. Also the decided gates remembered.
 * @param workers The notices sent at once.
 */
class NotificationOutbox(
    private val push: PushSender,
    private val mail: MailSender,
    private val logger: Logger,
    private val retryDelays: List<Duration> = RETRY_DELAYS,
    private val capacity: Int = CAPACITY,
    private val workers: Int = WORKERS,
) {
    private class Attempt(val notice: Notice, val number: Int)

    private val queue = Channel<Attempt>(capacity)
    private val closing = Mutex()
    private val closed = LinkedHashSet<Uuid>()

    /**
     * Queues a notice; never waits.
     *
     * @param notice The notice.
     *
     * @return Whether it was queued: false when the queue is full, and the notice is dropped.
     */
    fun offer(notice: Notice): Boolean {
        val queued = queue.trySend(Attempt(notice, 1)).isSuccess
        if (!queued) logger.error("notification dropped, queue full", fields(Attempt(notice, 1)))
        return queued
    }

    /**
     * Sends nothing more about a decided gate: its queued notices and retries are dropped. The [capacity] most recent
     * gates are remembered; a notice of an older one, which no retry reaches any more, would be sent.
     *
     * @param gateId The gate.
     */
    suspend fun close(gateId: Uuid) {
        closing.withLock {
            closed += gateId
            if (closed.size > capacity) closed.remove(closed.first())
        }
    }

    /** Sends the queued notices, [workers] at a time, until the calling coroutine is cancelled. */
    suspend fun run() {
        coroutineScope {
            val retries = this
            repeat(workers) {
                launch {
                    while (true) deliver(queue.receive(), retries)
                }
            }
        }
    }

    private suspend fun deliver(attempt: Attempt, retries: CoroutineScope) {
        if (closing.withLock { attempt.notice.gateId in closed }) return
        try {
            attempt.notice.deliver(push, mail).forEach(::offer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The type only: a provider's message may quote what it was sent.
            // Push refusals carry our own words (Expo's status or ticket code), never a token: kept for the operator.
            val reason = (error as? PushNotSentException)?.let { mapOf("reason" to it.message) }.orEmpty()
            val failed = fields(attempt) + ("error" to error::class.simpleName) + reason
            val pause = retryDelays.getOrNull(attempt.number - 1)
            if (pause == null) {
                logger.error("notification given up", failed)
                return
            }
            logger.warn("notification failed, tried again", failed + ("retry_in" to pause.toString()))
            retries.launch {
                delay(pause)
                queue.send(Attempt(attempt.notice, attempt.number + 1))
            }
        }
    }

    private fun fields(attempt: Attempt): Map<String, Any?> =
        mapOf("kind" to attempt.notice.kind, "gate_id" to attempt.notice.gateId.toString(), "attempt" to attempt.number)
}

/** Four retries, the last one about a quarter of an hour after the request: a provider's short outage passes. */
private val RETRY_DELAYS = listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes)

private const val CAPACITY = 10_000

/** A provider that takes its full timeout holds one worker of four: the other notices still go. */
private const val WORKERS = 4
