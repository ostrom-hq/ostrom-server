// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.server.integrations.slack.domain.SlackApprovalChannel
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessage
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackNotificationChannel
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.SlackTexts
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.kernel.events.GateSummary
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The `slack` notification channel, the yes requests' messages and their update once decided. */
class SlackChannelsTest {
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private val carol = Uuid.random()
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val settings = SlackInstanceSettings()
    private val apps = SlackApps(settings)
    private val links = InMemorySlackLinks().apply {
        links[org to bob] = StoredSlackLink(bob, "U0BOB", now)
    }
    private val messages = InMemorySlackMessages()
    private val texts = SlackTexts("https://console.ostrom.test")
    private val api = FakeSlackApi()

    private val gate = GateSummary(
        Uuid.random().toString(), true, listOf("ana"), "Push to main", "acme/app", listOf("bob"),
    )
    private val delivery = ApprovalDelivery(
        Uuid.random(), org, bob, "bob", Uuid.random(), Uuid.parse(gate.id), "slack", 0, 1,
        "ana asks for your yes", "Push to main · acme/app", now,
    )

    private fun notification(member: Uuid) = Notification(
        Uuid.random(), org, member, NotificationKind.WAITING, "Ship", "Review", null, "w-1", "wait:w-1", now, 1,
    )

    @Test
    fun aMemberWhoLinkedSlackGetsOneDirectMessagePerNotification() = runTest {
        val channel = SlackNotificationChannel(apps, api, links, texts)
        assertEquals("slack", channel.id)
        assertEquals(setOf(bob), channel.reachable(org, setOf(bob, carol)))
        assertEquals(emptySet(), channel.reachable(org, emptySet()))
        val first = notification(bob)
        val second = notification(bob)
        val unlinked = notification(carol)

        val results = channel.deliver(listOf(first, second, unlinked))

        assertEquals(ChannelResult.DELIVERED, results[first.id])
        assertEquals(ChannelResult.DELIVERED, results[second.id])
        assertEquals(ChannelResult.failed("slack_not_linked"), results[unlinked.id])
        assertEquals(listOf("U0BOB", "U0BOB"), api.posted.map { it.first })
    }

    @Test
    fun aSlackFailureIsRetriedWhileSlackMayTakeItAndNothingGoesWithoutTheApp() = runTest {
        val channel = SlackNotificationChannel(apps, api, links, texts)
        val one = notification(bob)
        api.postFailure = SlackCallException("ratelimited", retryable = true)
        assertEquals(ChannelResult.retry("ratelimited"), channel.deliver(listOf(one))[one.id])
        api.postFailure = SlackCallException("account_inactive", retryable = false)
        assertEquals(ChannelResult.failed("account_inactive"), channel.deliver(listOf(one))[one.id])
        settings.configured = false
        assertEquals(emptySet(), channel.reachable(org, setOf(bob)))
        assertEquals(ChannelResult.failed("slack_not_configured"), channel.deliver(listOf(one))[one.id])
    }

    @Test
    fun aYesRequestIsPostedAndKept() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        assertEquals(setOf(bob), channel.reachable(org, setOf(bob, carol)))
        assertEquals(emptySet(), channel.reachable(org, emptySet()))
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c/yes/${delivery.approvalId}"))
        val posted = SlackPosted("D-U0BOB", "1700000000.1")
        val kept = SlackApprovalMessage(delivery.approvalId, delivery.gateId, posted)
        assertEquals(listOf(kept), messages.messages.values.toList())
        assertTrue(api.posted.single().second.blocks != null, "With its buttons")
        val carols = delivery.copy(memberId = carol, approvalId = Uuid.random())
        assertEquals(ChannelResult.failed("slack_not_linked"), channel.send(carols, gate, "https://c"))
        val fresh = delivery.copy(approvalId = Uuid.random())
        api.postFailure = SlackCallException("slack_unavailable", retryable = true)
        assertEquals(ChannelResult.retry("slack_unavailable"), channel.send(fresh, gate, "https://c"))
        settings.configured = false
        assertEquals(ChannelResult.failed("slack_not_configured"), channel.send(fresh, gate, "https://c"))
        assertEquals(emptySet(), channel.reachable(org, setOf(bob)))
    }

    @Test
    fun aRequestAlreadyKeptIsNeverSentASecondTime() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        // The queue lost the first outcome (its record failed): the attempt runs again.
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(1, api.posted.size, "One direct message, one set of buttons")
        assertEquals(emptyList(), api.deleted)
    }

    @Test
    fun aMessageThatCannotBeKeptIsTakenBackSoTheRetrySendsTheOnlyOne() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1

        assertEquals(ChannelResult.retry("slack_not_kept"), channel.send(delivery, gate, "https://c"))
        assertEquals(api.deleted.single(), SlackPosted("D-U0BOB", "1700000000.1"), "The first one is taken back")
        assertEquals(emptyMap(), messages.messages)

        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(2, api.posted.size)
        assertEquals(SlackPosted("D-U0BOB", "1700000000.2"), messages.messages.values.single().posted)
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun aMessageThatCannotBeKeptNorTakenBackForGoodCountsAsToldRatherThanSentTwice() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1
        api.deleteFailure = SlackCallException("cant_delete_message", retryable = false)

        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(1, api.posted.size)
    }

    @Test
    fun aDeleteSlackFailsOnIsTriedAgainAndOneAlreadyGoneIsSentAgain() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1
        api.deleteFailures = 2
        assertEquals(ChannelResult.retry("slack_not_kept"), channel.send(delivery, gate, "https://c"))
        assertEquals(listOf(SlackPosted("D-U0BOB", "1700000000.1")), api.deleted, "Taken back at the third try")

        messages.failingSaves = 1
        api.deleteFailures = 5
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"), "Still failing: told")
        assertEquals(2, api.posted.size)
        api.deleteFailures = 0

        messages.failingSaves = 1
        api.deleteFailure = SlackCallException("message_not_found", retryable = false)
        assertEquals(ChannelResult.retry("slack_not_kept"), channel.send(delivery, gate, "https://c"))
    }

    @Test
    fun aCancelledSaveIsNotMistakenForAFailedOne() = runTest {
        val cancelling = object : SlackApprovalMessagesRepository by messages {
            override suspend fun save(orgId: Uuid, message: SlackApprovalMessage) = throw CancellationException("Stop")
        }
        val channel = SlackApprovalChannel(apps, api, links, cancelling, texts)
        assertFailsWith<CancellationException> { channel.send(delivery, gate, "https://c") }
        assertEquals(emptyList(), api.deleted, "Cancelled, not taken back")
    }

    @Test
    fun anAnswerClosesTheKeptMessageWithItsOutcomeAndRetriesWhileSlackMayTakeIt() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        val answer = delivery.copy(decision = "rejected")
        assertEquals(ChannelResult.DELIVERED, channel.decided(answer), "Nothing was kept: nothing to close")
        assertEquals(emptyList(), api.updated)
        channel.send(delivery, gate, "https://c")
        val posted = messages.messages.values.single().posted

        assertEquals(ChannelResult.DELIVERED, channel.decided(answer))
        assertEquals(posted, api.updated.single().first)
        assertEquals("*ana asks for your yes*\nRejected by bob.", api.updated.single().second.text)
        assertEquals(null, api.updated.single().second.blocks?.toString()?.takeIf { "button" in it }, "No buttons")

        assertEquals(ChannelResult.DELIVERED, channel.decided(delivery.copy(decision = "approved")))
        assertEquals(ChannelResult.DELIVERED, channel.decided(delivery.copy(decision = "closed")))
        assertEquals(
            listOf(
                "*ana asks for your yes*\nRejected by bob.",
                "*ana asks for your yes*\nApproved by bob.",
                "*ana asks for your yes*\nNo longer waits for an answer.",
            ),
            api.updated.map { it.second.text },
        )
        api.failure = SlackCallException("ratelimited", retryable = true)
        assertEquals(ChannelResult.retry("ratelimited"), channel.decided(answer))
        api.failure = SlackCallException("message_not_found", retryable = false)
        assertEquals(ChannelResult.failed("message_not_found"), channel.decided(answer))
        api.failure = null
        settings.configured = false
        assertEquals(ChannelResult.failed("slack_not_configured"), channel.decided(answer))
        assertEquals(3, api.updated.size)
    }
}
