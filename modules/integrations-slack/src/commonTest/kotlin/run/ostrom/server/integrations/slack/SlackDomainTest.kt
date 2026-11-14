// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.core.api.models.SlackLink
import run.ostrom.server.integrations.slack.domain.GetSlackLinkUseCaseImpl
import run.ostrom.server.integrations.slack.domain.LinkSlackUseCaseImpl
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackEmailUnprovenException
import run.ostrom.server.integrations.slack.domain.SlackUserTakenException
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.domain.SlackNotConfiguredException
import run.ostrom.server.integrations.slack.domain.SlackSettings
import run.ostrom.server.integrations.slack.domain.SlackTexts
import run.ostrom.server.integrations.slack.domain.SlackUnavailableException
import run.ostrom.server.integrations.slack.domain.SlackUser
import run.ostrom.server.integrations.slack.domain.SlackUserNotFoundException
import run.ostrom.server.integrations.slack.domain.UnlinkSlackUseCaseImpl
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Slack app's settings, the messages, and a member's link by their proven address. */
class SlackDomainTest {
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val texts = SlackTexts("https://console.ostrom.test")
    private val settings = SlackInstanceSettings()
    private val links = InMemorySlackLinks()

    private fun member(email: String?) = SlackMember(bob, "bob", OrgRole.MEMBER, email, "Acme")

    private fun link(api: FakeSlackApi, email: String? = "bob@example.com") = LinkSlackUseCaseImpl(
        SlackApps(settings), api, MapSlackPeople(mapOf(bob to member(email))), links, texts, clock,
    )

    @Test
    fun theSettingsTakeABotTokenAndASigningSecretAndAskSlackAboutTheToken() = runTest {
        assertNull(SlackSettings.botTokenRefusal(BOT_TOKEN))
        assertEquals("A Slack bot token starts with xoxb-.", SlackSettings.botTokenRefusal("xoxp-1111-2222-abcd"))
        assertNull(SlackSettings.signingSecretRefusal(SIGNING_SECRET))
        assertTrue(SlackSettings.signingSecretRefusal("short") != null)
        assertTrue(SlackSettings.signingSecretRefusal("with spaces in it 0123456789") != null)
        val section = SlackSettings.section { if (it == BOT_TOKEN) null else "refused" }
        assertEquals(listOf("botToken", "signingSecret"), section.fields.map { it.name })
        assertTrue(section.fields.all { it.secret })
        assertNull(section.field("botToken")?.refusal(BOT_TOKEN))
        assertEquals("refused", section.field("botToken")?.refusal("xoxb-9999-8888-ffffffff"))
    }

    @Test
    fun theAppIsConfiguredOnlyWithBothSecretsAndNeverShowsThem() = runTest {
        val app = SlackApps(settings).current()
        assertEquals(BOT_TOKEN, app?.botToken)
        assertEquals("SlackApp(***)", app.toString())
        settings.configured = false
        assertNull(SlackApps(settings).current())
    }

    @Test
    fun aNotificationIsItsTitleItsTextAndALinkWithAPersonsTextEscaped() {
        val notification = Notification(
            Uuid.random(), org, bob, NotificationKind.WAITING, "Ship <@here>", "Review & merge", "s-1", "w-1",
            "wait:w-1", now, 1,
        )
        assertEquals(
            "*Ship &lt;@here&gt;*\nReview &amp; merge\n<https://console.ostrom.test/waits/w-1|Open in Ostrom>",
            texts.notification(notification).text,
        )
        val session = notification.copy(waitId = null, body = "")
        assertEquals(
            "*Ship &lt;@here&gt;*\n<https://console.ostrom.test/sessions/s-1|Open in Ostrom>",
            texts.notification(session).text,
        )
        assertEquals("*Ship &lt;@here&gt;*", texts.notification(session.copy(sessionId = null)).text)
    }

    @Test
    fun aYesRequestOpensTheConsoleToApproveAndCarriesItsRequestToReject() {
        val approval = Uuid.random()
        val message = texts.approval(org, approval, "ana asks for your yes", "Push to main", "https://c/yes/$approval")
        val actions = message.blocks!![1].jsonObject["elements"]!!.jsonArray
        val approve = actions[0].jsonObject
        val reject = actions[1].jsonObject
        assertEquals("https://c/yes/$approval", approve["url"]?.jsonPrimitive?.content)
        assertEquals(SlackTexts.REJECT_ACTION, reject["action_id"]?.jsonPrimitive?.content)
        assertEquals("$org:$approval", reject["value"]?.jsonPrimitive?.content)
        assertEquals("*ana asks for your yes*\nPush to main", message.text)
        val closed = texts.decided("Push to main", "Rejected by bob.")
        assertEquals(1, closed.blocks!!.size, "No buttons once decided")
        assertTrue("*bob*" in texts.linked("bob", "Acme").text)
    }

    @Test
    fun aMemberIsLinkedToTheActiveSlackPersonOfTheirProvenAddressAndToldInSlack() = runTest {
        val api = FakeSlackApi(mapOf("bob@example.com" to SlackUser("U0BOB", deleted = false, bot = false)))
        val linked = link(api)(org, bob)
        assertEquals(SlackLink(bob.toString(), true, true, "U0BOB", now.toString()), linked)
        assertEquals(listOf("U0BOB"), api.posted.map { it.first }, "The Slack account is told")
        assertEquals(linked, GetSlackLinkUseCaseImpl(SlackApps(settings), links)(org, bob))
        api.postFailure = SlackCallException("ratelimited", retryable = true)
        assertEquals(linked, link(api)(org, bob), "Linked again, the message is best effort")
        val carol = Uuid.random()
        links.links[org to carol] = StoredSlackLink(carol, "U0BOB", now)
        links.links.remove(org to bob)
        assertFailsWith<SlackUserTakenException> { link(api)(org, bob) }
        links.links.remove(org to carol)
        link(api)(org, bob)
        UnlinkSlackUseCaseImpl(links)(org, bob)
        UnlinkSlackUseCaseImpl(links)(org, bob)
        assertEquals(SlackLink(bob.toString(), true, false), GetSlackLinkUseCaseImpl(SlackApps(settings), links)(org, bob))
    }

    @Test
    fun noLinkWithoutTheAppAProvenAddressOrAnActivePerson() = runTest {
        val users = mapOf(
            "bot@example.com" to SlackUser("U0BOT", deleted = false, bot = true),
            "gone@example.com" to SlackUser("U0OLD", deleted = true, bot = false),
        )
        val api = FakeSlackApi(users)
        assertFailsWith<SlackEmailUnprovenException> { link(api, email = null)(org, bob) }
        assertFailsWith<SlackUserNotFoundException> { link(api)(org, bob) }
        assertFailsWith<SlackUserNotFoundException> { link(api, "bot@example.com")(org, bob) }
        assertFailsWith<SlackUserNotFoundException> { link(api, "gone@example.com")(org, bob) }
        val stranger = LinkSlackUseCaseImpl(SlackApps(settings), api, MapSlackPeople(emptyMap()), links, texts, clock)
        assertFailsWith<SlackUserNotFoundException> { stranger(org, bob) }
        api.failure = SlackCallException("slack_unreachable", retryable = true)
        assertFailsWith<SlackUnavailableException> { link(api)(org, bob) }
        api.failure = SlackCallException("missing_scope", retryable = false)
        assertFailsWith<SlackNotConfiguredException> { link(api)(org, bob) }
        settings.configured = false
        assertFailsWith<SlackNotConfiguredException> { link(api)(org, bob) }
        assertEquals(SlackLink(bob.toString(), false, false), GetSlackLinkUseCaseImpl(SlackApps(settings), links)(org, bob))
        assertTrue(links.links.isEmpty())
    }
}
