// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.server.integrations.discord.domain.DiscordApprovalChannel
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessage
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordApps
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordNotificationChannel
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordTexts
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
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

/** The `discord` notification channel, the yes requests' messages and their update once decided. */
class DiscordChannelsTest {
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private val carol = Uuid.random()
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val settings = DiscordInstanceSettings()
    private val apps = DiscordApps(settings)
    private val links = InMemoryDiscordLinks().apply {
        links[org to bob] = StoredDiscordLink(bob, BOB_ID, "bob", now)
    }
    private val messages = InMemoryDiscordMessages()
    private val texts = DiscordTexts("https://console.ostrom.test")
    private val api = FakeDiscordApi()

    private val gate = GateSummary(
        Uuid.random().toString(), true, listOf("ana"), "Push to main", "acme/app", listOf("bob"),
    )
    private val delivery = ApprovalDelivery(
        Uuid.random(), org, bob, "bob", Uuid.random(), Uuid.parse(gate.id), "discord", 0, 1,
        "ana asks for your yes", "Push to main · acme/app", now,
    )

    private fun notification(member: Uuid, orgId: Uuid = org) = Notification(
        Uuid.random(), orgId, member, NotificationKind.WAITING, "Ship", "Review", null, "w-1", "wait:w-1", now, 1,
    )

    @Test
    fun aMemberWhoLinkedDiscordGetsOneDirectMessagePerNotificationInTheChannelOpenedOnce() = runTest {
        val channel = DiscordNotificationChannel(apps, api, links, texts)
        assertEquals("discord", channel.id)
        assertEquals(setOf(bob), channel.reachable(org, setOf(bob, carol)))
        assertEquals(emptySet(), channel.reachable(org, emptySet()))
        val first = notification(bob)
        val second = notification(bob)
        val unlinked = notification(carol)

        val results = channel.deliver(listOf(first, second, unlinked))

        assertEquals(ChannelResult.DELIVERED, results[first.id])
        assertEquals(ChannelResult.DELIVERED, results[second.id])
        assertEquals(ChannelResult.failed("discord_not_linked"), results[unlinked.id])
        assertEquals(listOf("DM-$BOB_ID", "DM-$BOB_ID"), api.posted.map { it.first })
        assertEquals("DM-$BOB_ID", links.links[org to bob]?.dmChannelId, "The channel is kept with the link")
        channel.deliver(listOf(notification(bob)))
        assertEquals(listOf(BOB_ID, BOB_ID), api.opened, "Opened per batch until kept, then reused")
    }

    @Test
    fun aChannelThatCannotBeKeptIsUsedAllTheSame() = runTest {
        val channel = DiscordNotificationChannel(apps, api, links, texts)
        links.failingChannelSaves = 1
        val one = notification(bob)
        assertEquals(ChannelResult.DELIVERED, channel.deliver(listOf(one))[one.id])
        assertEquals(null, links.links[org to bob]?.dmChannelId)
        assertEquals(listOf("DM-$BOB_ID"), api.posted.map { it.first })
    }

    @Test
    fun aCancelledChannelSaveIsNotMistakenForAFailedOne() = runTest {
        val cancelling = object : DiscordLinksRepository by links {
            override suspend fun saveDmChannel(orgId: Uuid, memberId: Uuid, discordUserId: String, channelId: String) =
                throw CancellationException("Stop")
        }
        val channel = DiscordNotificationChannel(apps, api, cancelling, texts)
        assertFailsWith<CancellationException> { channel.deliver(listOf(notification(bob))) }
        assertEquals(emptyList(), api.posted)
    }

    @Test
    fun oneOrganizationsFailedReadRetriesOnlyItsOwnBatch() = runTest {
        val other = Uuid.random()
        links.links[other to carol] = StoredDiscordLink(carol, "300000000000000005", "carol", now)
        links.failingReads += org
        val channel = DiscordNotificationChannel(apps, api, links, texts)
        val ours = notification(bob)
        val theirs = notification(carol, other)
        val results = channel.deliver(listOf(ours, theirs))
        assertEquals(ChannelResult.retry("discord_store_failed"), results[ours.id])
        assertEquals(ChannelResult.DELIVERED, results[theirs.id])
    }

    @Test
    fun aCancelledReadIsNotMistakenForAFailedOne() = runTest {
        val cancelling = object : DiscordLinksRepository by links {
            override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>) = throw CancellationException("Stop")
        }
        val channel = DiscordNotificationChannel(apps, api, cancelling, texts)
        assertFailsWith<CancellationException> { channel.deliver(listOf(notification(bob))) }
    }

    @Test
    fun aDiscordFailureIsRetriedWhileDiscordMayTakeItAndNothingGoesWithoutTheApplication() = runTest {
        val channel = DiscordNotificationChannel(apps, api, links, texts)
        val one = notification(bob)
        api.postFailure = DiscordCallException("discord_rate_limited", retryable = true)
        assertEquals(ChannelResult.retry("discord_rate_limited"), channel.deliver(listOf(one))[one.id])
        api.postFailure = DiscordCallException("discord_50007", retryable = false)
        assertEquals(ChannelResult.failed("discord_50007"), channel.deliver(listOf(one))[one.id])
        api.postFailure = null
        links.links[org to bob] = links.links.getValue(org to bob).copy(dmChannelId = null)
        api.openFailure = DiscordCallException("discord_unavailable", retryable = true)
        assertEquals(ChannelResult.retry("discord_unavailable"), channel.deliver(listOf(one))[one.id])
        settings.configured = false
        assertEquals(emptySet(), channel.reachable(org, setOf(bob)))
        assertEquals(ChannelResult.failed("discord_not_configured"), channel.deliver(listOf(one))[one.id])
    }

    @Test
    fun aYesRequestIsPostedAndKept() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        assertEquals("discord", channel.id)
        assertEquals(setOf(bob), channel.reachable(org, setOf(bob, carol)))
        assertEquals(emptySet(), channel.reachable(org, emptySet()))
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c/yes/${delivery.approvalId}"))
        val posted = DiscordPosted("DM-$BOB_ID", "1")
        val kept = DiscordApprovalMessage(delivery.approvalId, delivery.gateId, posted)
        assertEquals(listOf(kept), messages.messages.values.toList())
        assertTrue(api.posted.single().second.components != null, "With its buttons")
        val carols = delivery.copy(memberId = carol, approvalId = Uuid.random())
        assertEquals(ChannelResult.failed("discord_not_linked"), channel.send(carols, gate, "https://c"))
        val fresh = delivery.copy(approvalId = Uuid.random())
        api.postFailure = DiscordCallException("discord_unavailable", retryable = true)
        assertEquals(ChannelResult.retry("discord_unavailable"), channel.send(fresh, gate, "https://c"))
        settings.configured = false
        assertEquals(ChannelResult.failed("discord_not_configured"), channel.send(fresh, gate, "https://c"))
        assertEquals(emptySet(), channel.reachable(org, setOf(bob)))
    }

    @Test
    fun aRequestAlreadyKeptIsNeverSentASecondTime() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        // The queue lost the first outcome (its record failed): the attempt runs again.
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(1, api.posted.size, "One direct message, one set of buttons")
        assertEquals(emptyList(), api.deleted)
    }

    @Test
    fun aMessageThatCannotBeKeptIsTakenBackSoTheRetrySendsTheOnlyOne() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1

        assertEquals(ChannelResult.retry("discord_not_kept"), channel.send(delivery, gate, "https://c"))
        assertEquals(DiscordPosted("DM-$BOB_ID", "1"), api.deleted.single(), "The first one is taken back")
        assertEquals(emptyMap(), messages.messages)

        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(2, api.posted.size)
        assertEquals(DiscordPosted("DM-$BOB_ID", "2"), messages.messages.values.single().posted)
        assertEquals(1, api.deleted.size)
    }

    @Test
    fun aMessageThatCannotBeKeptNorTakenBackForGoodCountsAsToldRatherThanSentTwice() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1
        api.deleteFailure = DiscordCallException("discord_50013", retryable = false)

        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"))
        assertEquals(1, api.posted.size)
    }

    @Test
    fun aDeleteDiscordFailsOnIsTriedAgainAndOneAlreadyGoneIsSentAgain() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        messages.failingSaves = 1
        api.deleteFailures = 2
        assertEquals(ChannelResult.retry("discord_not_kept"), channel.send(delivery, gate, "https://c"))
        assertEquals(listOf(DiscordPosted("DM-$BOB_ID", "1")), api.deleted, "Taken back at the third try")

        messages.failingSaves = 1
        api.deleteFailures = 5
        assertEquals(ChannelResult.DELIVERED, channel.send(delivery, gate, "https://c"), "Still failing: told")
        assertEquals(2, api.posted.size)
        api.deleteFailures = 0

        messages.messages.clear()
        messages.failingSaves = 1
        api.deleteFailure = DiscordCallException("discord_10008", retryable = false)
        assertEquals(ChannelResult.retry("discord_not_kept"), channel.send(delivery, gate, "https://c"))
    }

    @Test
    fun aCancelledSaveIsNotMistakenForAFailedOne() = runTest {
        val cancelling = object : DiscordApprovalMessagesRepository by messages {
            override suspend fun save(orgId: Uuid, message: DiscordApprovalMessage) =
                throw CancellationException("Stop")
        }
        val channel = DiscordApprovalChannel(apps, api, links, cancelling, texts)
        assertFailsWith<CancellationException> { channel.send(delivery, gate, "https://c") }
        assertEquals(emptyList(), api.deleted, "Cancelled, not taken back")
    }

    @Test
    fun anAnswerClosesTheKeptMessageWithItsOutcomeAndRetriesWhileDiscordMayTakeIt() = runTest {
        val channel = DiscordApprovalChannel(apps, api, links, messages, texts)
        val answer = delivery.copy(decision = "rejected")
        assertEquals(ChannelResult.DELIVERED, channel.decided(answer), "Nothing was kept: nothing to close")
        assertEquals(emptyList(), api.edited)
        channel.send(delivery, gate, "https://c")
        val posted = messages.messages.values.single().posted

        assertEquals(ChannelResult.DELIVERED, channel.decided(answer))
        assertEquals(posted, api.edited.single().first)
        assertEquals("**ana asks for your yes**\nRejected by bob.", api.edited.single().second.content)
        assertEquals(0, api.edited.single().second.components?.size, "No buttons")

        assertEquals(ChannelResult.DELIVERED, channel.decided(delivery.copy(decision = "approved")))
        assertEquals(ChannelResult.DELIVERED, channel.decided(delivery.copy(decision = "closed")))
        assertEquals(
            listOf(
                "**ana asks for your yes**\nRejected by bob.",
                "**ana asks for your yes**\nApproved by bob.",
                "**ana asks for your yes**\nNo longer waits for an answer.",
            ),
            api.edited.map { it.second.content },
        )
        api.failure = DiscordCallException("discord_rate_limited", retryable = true)
        assertEquals(ChannelResult.retry("discord_rate_limited"), channel.decided(answer))
        api.failure = DiscordCallException("discord_50001", retryable = false)
        assertEquals(ChannelResult.failed("discord_50001"), channel.decided(answer))
        api.failure = DiscordCallException("discord_10008", retryable = false)
        assertEquals(ChannelResult.DELIVERED, channel.decided(answer), "Deleted by the approver: nothing to close")
        api.failure = null
        settings.configured = false
        assertEquals(ChannelResult.failed("discord_not_configured"), channel.decided(answer))
        assertEquals(3, api.edited.size)
    }

    private companion object {
        const val BOB_ID = "300000000000000003"
    }
}
