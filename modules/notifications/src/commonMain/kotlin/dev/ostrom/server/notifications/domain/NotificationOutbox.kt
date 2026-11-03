// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** One approver's notification about one gate: the push to their phones, or the e-mail. */
sealed interface Notice {
    /** The gate it is about, in the logs. */
    val gateId: Uuid

    /** `push` or `email`, in the logs. */
    val kind: String

    /**
     * Sends it through its provider.
     *
     * @param push The push service.
     * @param mail The mail provider.
     */
    suspend fun deliver(push: PushSender, mail: MailSender)

    /**
     * The pushes to an approver's phones, sent together.
     *
     * @property gateId The gate.
     * @property messages One per phone.
     */
    data class Push(override val gateId: Uuid, val messages: List<PushMessage>) : Notice {
        override val kind: String get() = "push"

        override suspend fun deliver(push: PushSender, mail: MailSender) = push.send(messages)
    }

    /**
     * The e-mail to an approver.
     *
     * @property gateId The gate.
     * @property mail The e-mail.
     */
    data class Email(override val gateId: Uuid, val mail: Mail) : Notice {
        override val kind: String get() = "email"

        override suspend fun deliver(push: PushSender, mail: MailSender) = mail.send(this.mail)
    }
}

/**
 * Sends the notifications apart from the request that causes them: [offer] queues and returns at once, so a slow or
 * failing push service or mail provider never holds a request for a yes, which is stored and waits in the console and
 * the app's queue anyway. A notice that fails is logged (its kind, gate and attempt, never a token or an address) and
 * tried again after each of [retryDelays], then given up. The queue lives in the server's memory: a restart loses
 * the notices not sent yet, never a request.
 *
 * @param push The push service.
 * @param mail The mail provider.
 * @param logger Logs each failure and each notice given up.
 * @param retryDelays The pause before each new attempt; their count is the number of retries.
 * @param capacity The notices queued at most; one more is dropped and logged.
 */
class NotificationOutbox(
    private val push: PushSender,
    private val mail: MailSender,
    private val logger: Logger,
    private val retryDelays: List<Duration> = RETRY_DELAYS,
    capacity: Int = CAPACITY,
) {
    private class Attempt(val notice: Notice, val number: Int)

    private val queue = Channel<Attempt>(capacity)

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

    /** Sends the queued notices, one after the other, until the calling coroutine is cancelled. */
    suspend fun run() {
        coroutineScope {
            while (true) deliver(queue.receive(), this)
        }
    }

    private suspend fun deliver(attempt: Attempt, retries: CoroutineScope) {
        try {
            attempt.notice.deliver(push, mail)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The type only: a provider's message may quote what it was sent.
            val failed = fields(attempt) + ("error" to error::class.simpleName)
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
