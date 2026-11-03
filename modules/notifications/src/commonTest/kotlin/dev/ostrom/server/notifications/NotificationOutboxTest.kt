// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.notifications.domain.Notice
import dev.ostrom.server.notifications.domain.NotificationOutbox
import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class NotificationOutboxTest {
    private val gate = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val token = "ExponentPushToken[secret-token-1]"
    private val message = PushMessage(token, "ana asks for your yes", "Push to main", mapOf("kind" to "approval"))
    private val mail = Mail("bob@example.com", "ana asks for your yes", "<p>hi</p>", "hi")
    private val logger = RecordingLogger()

    private fun logged(level: LogLevel) = logger.events.filter { it.first == level }

    @Test
    fun aQueuedPushAndEmailAreSentAtOnceInTheOrderTheyCame() = runTest {
        val push = RecordingPushSender()
        val email = RecordingMailSender()
        val outbox = NotificationOutbox(push, email, logger)
        backgroundScope.launch { outbox.run() }

        assertTrue(outbox.offer(Notice.Push(gate, listOf(message))))
        assertTrue(outbox.offer(Notice.Email(gate, mail)))
        runCurrent()

        assertEquals(listOf(listOf(message)), push.calls)
        assertEquals(listOf(mail), email.calls)
        assertTrue(logger.events.isEmpty(), "Nothing to report: ${logger.events}")
    }

    @Test
    fun aFailingProviderIsTriedAgainAfterEachPauseWithoutStoppingTheOtherNotices() = runTest {
        val push = RecordingPushSender(failures = 2)
        val email = RecordingMailSender()
        val outbox = NotificationOutbox(push, email, logger)
        backgroundScope.launch { outbox.run() }

        outbox.offer(Notice.Push(gate, listOf(message)))
        outbox.offer(Notice.Email(gate, mail))
        runCurrent()
        assertEquals(1, push.calls.size)
        assertEquals(listOf(mail), email.calls, "The e-mail does not wait for the push")

        advanceTimeBy(4.seconds)
        assertEquals(1, push.calls.size, "Not before the first pause")
        advanceTimeBy(2.seconds)
        assertEquals(2, push.calls.size, "Again after 5 s")
        advanceTimeBy(31.seconds)
        assertEquals(3, push.calls.size, "Again after 30 s more, and sent")
        assertEquals(listOf(message), push.delivered.tryReceive().getOrNull())

        val warnings = logged(LogLevel.WARN)
        assertEquals(listOf("notification failed, tried again"), warnings.map { it.second }.distinct())
        assertEquals(
            mapOf(
                "kind" to "push",
                "gate_id" to gate.toString(),
                "attempt" to 1,
                "error" to "PushNotSentException",
                "retry_in" to "5s",
            ),
            warnings.first().third,
        )
        assertEquals(listOf(1, 2), warnings.map { it.third["attempt"] })
        assertFalse(logger.events.toString().contains(token), "A push token is never logged")
    }

    @Test
    fun aNoticeIsGivenUpAfterItsLastRetryAndTheReasonLoggedWithoutTheAddress() = runTest {
        val push = RecordingPushSender()
        val email = RecordingMailSender(failures = 10)
        val outbox = NotificationOutbox(push, email, logger)
        backgroundScope.launch { outbox.run() }

        outbox.offer(Notice.Email(gate, mail))
        advanceTimeBy(13.minutes)
        runCurrent()

        assertEquals(5, email.calls.size, "The first attempt and four retries")
        val given = logged(LogLevel.ERROR).single()
        assertEquals("notification given up", given.second)
        assertEquals(
            mapOf("kind" to "email", "gate_id" to gate.toString(), "attempt" to 5, "error" to "MailNotSentException"),
            given.third,
        )
        assertFalse(logger.events.toString().contains("bob@example.com"), "An address is never logged")
    }

    @Test
    fun aFullQueueDropsTheNoticeAndSaysSo() = runTest {
        val outbox = NotificationOutbox(RecordingPushSender(), RecordingMailSender(), logger, capacity = 1)

        assertTrue(outbox.offer(Notice.Email(gate, mail)))
        assertFalse(outbox.offer(Notice.Push(gate, listOf(message))))

        val dropped = logged(LogLevel.ERROR).single()
        assertEquals("notification dropped, queue full", dropped.second)
        assertEquals(mapOf("kind" to "push", "gate_id" to gate.toString(), "attempt" to 1), dropped.third)
    }

    @Test
    fun aCancellationIsNeverTakenForAFailure() = runTest {
        val cancelling = object : PushSender {
            override suspend fun send(messages: List<PushMessage>) = throw CancellationException("stop")
        }
        val unused = object : MailSender {
            override suspend fun send(mail: Mail) = error("unused")
        }
        val outbox = NotificationOutbox(cancelling, unused, logger)
        outbox.offer(Notice.Push(gate, listOf(message)))

        assertFailsWith<CancellationException> { outbox.run() }
        assertTrue(logger.events.isEmpty(), "Not logged as a failure: ${logger.events}")
    }

    @Test
    fun eachNoticeSaysItsKind() {
        assertEquals("push", Notice.Push(gate, emptyList()).kind)
        assertEquals("email", Notice.Email(gate, mail).kind)
    }
}
