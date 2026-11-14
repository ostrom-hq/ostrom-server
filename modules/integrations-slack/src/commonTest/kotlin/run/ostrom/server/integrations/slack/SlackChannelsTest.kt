// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.test.runTest
import run.ostrom.server.integrations.slack.domain.SlackApprovalChannel
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessage
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackGateChannel
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
    fun aYesRequestIsPostedKeptAndClosedOnceItsGateIsDecided() = runTest {
        val channel = SlackApprovalChannel(apps, api, links, messages, texts)
        assertEquals(setOf(bob), channel.reachable(org, setOf(bob, carol)))
        val gate = GateSummary(Uuid.random().toString(), true, listOf("ana"), "Push to main", "acme/app", listOf("bob"))
        val delivery = ApprovalDelivery(
            Uuid.random(), org, bob, "bob", Uuid.random(), Uuid.parse(gate.id), "slack", 0, 1,
            "ana asks for your yes", "Push to main · acme/app", now,
        )

        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c/yes/${delivery.approvalId}"))
        val posted = SlackPosted("D-U0BOB", "1700000000.1")
        assertEquals(listOf(SlackApprovalMessage(delivery.approvalId, delivery.gateId, posted)), messages.messages.values.toList())
        assertTrue(api.posted.single().second.blocks != null, "With its buttons")
        val carols = delivery.copy(memberId = carol)
        assertEquals(ChannelResult.failed("slack_not_linked"), channel.send(carols, gate, "https://c"))
        api.postFailure = SlackCallException("slack_unavailable", retryable = true)
        assertEquals(ChannelResult.retry("slack_unavailable"), channel.send(delivery, gate, "https://c"))
        settings.configured = false
        assertEquals(ChannelResult.failed("slack_not_configured"), channel.send(delivery, gate, "https://c"))
        assertEquals(emptySet(), channel.reachable(org, setOf(bob)))
    }

    @Test
    fun theGatesChannelOnlyClosesTheMessagesAndNeverThrows() = runTest {
        val gates = SlackGateChannel(apps, api, messages, texts)
        assertEquals("slack", gates.name)
        val gateId = Uuid.random()
        val posted = SlackPosted("D-U0BOB", "1.1")
        messages.messages[org to Uuid.random()] = SlackApprovalMessage(Uuid.random(), gateId, posted)
        val gate = sampleDecidedGate(org, gateId, now)

        gates.requested(gate, emptyList())
        gates.decided(gate, emptyList())

        assertEquals(listOf(posted), api.updated.map { it.first })
        assertTrue("Rejected by bob." in api.updated.single().second.text)
        api.failure = SlackCallException("message_not_found", retryable = false)
        gates.decided(gate, emptyList())
        gates.decided(gate.copy(id = Uuid.random()), emptyList())
        settings.configured = false
        gates.decided(gate, emptyList())
        assertEquals(1, api.updated.size)
    }
}
