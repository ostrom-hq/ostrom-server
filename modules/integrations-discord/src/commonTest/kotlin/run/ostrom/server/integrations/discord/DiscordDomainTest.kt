// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.core.api.models.DiscordLink
import run.ostrom.server.integrations.discord.domain.CompleteDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordApps
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordLinkBrowserMismatchException
import run.ostrom.server.integrations.discord.domain.DiscordLinkDeniedException
import run.ostrom.server.integrations.discord.domain.DiscordLinkNotFoundException
import run.ostrom.server.integrations.discord.domain.DiscordMember
import run.ostrom.server.integrations.discord.domain.DiscordNotConfiguredException
import run.ostrom.server.integrations.discord.domain.DiscordNotInGuildException
import run.ostrom.server.integrations.discord.domain.DiscordSettings
import run.ostrom.server.integrations.discord.domain.DiscordTexts
import run.ostrom.server.integrations.discord.domain.DiscordUnreachableException
import run.ostrom.server.integrations.discord.domain.DiscordUserTakenException
import run.ostrom.server.integrations.discord.domain.GetDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.StartDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
import run.ostrom.server.integrations.discord.domain.UnlinkDiscordUseCaseImpl
import run.ostrom.server.integrations.discord.domain.sha256Hex
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Discord application's settings, the messages, and a member's link by Discord's sign-in in their browser. */
class DiscordDomainTest {
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private var now = Instant.parse("2026-11-15T09:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val texts = DiscordTexts("https://console.ostrom.test")
    private val settings = DiscordInstanceSettings()
    private val apps = DiscordApps(settings)
    private val links = InMemoryDiscordLinks()
    private val attempts = InMemoryDiscordAttempts()
    private val api = FakeDiscordApi(members = setOf(BOB_ID))
    private var people = MapDiscordPeople(mapOf(bob to DiscordMember(bob, "bob", OrgRole.MEMBER, "Acme")))
    private val start = StartDiscordLinkUseCaseImpl(apps, attempts, REDIRECT, clock)

    private fun complete() = CompleteDiscordLinkUseCaseImpl(apps, api, attempts, people, links, texts, REDIRECT, clock)

    // A started link's state.
    private suspend fun started(session: String = SESSION): String =
        Url(start(org, bob, session)).parameters["state"]!!

    @Test
    fun theSettingsTakeTheApplicationsFiveFieldsAndAskDiscordAboutTheToken() = runTest {
        assertNull(DiscordSettings.botTokenRefusal(BOT_TOKEN))
        assertTrue(DiscordSettings.botTokenRefusal("short") != null)
        assertNull(DiscordSettings.snowflakeRefusal(APPLICATION_ID, "application id"))
        assertEquals(
            "A Discord server id is a number of 17 to 20 digits.",
            DiscordSettings.snowflakeRefusal("12345", "server id"),
        )
        assertNull(DiscordSettings.publicKeyRefusal(PUBLIC_KEY))
        assertTrue(DiscordSettings.publicKeyRefusal(PUBLIC_KEY.dropLast(1) + "g") != null)
        assertNull(DiscordSettings.clientSecretRefusal(CLIENT_SECRET))
        assertTrue(DiscordSettings.clientSecretRefusal("with spaces in it 0123456789") != null)
        val section = DiscordSettings.section { if (it == BOT_TOKEN) null else "refused" }
        assertEquals(
            listOf("botToken", "applicationId", "publicKey", "clientSecret", "guildId"),
            section.fields.map { it.name },
        )
        assertEquals(listOf("botToken", "clientSecret"), section.fields.filter { it.secret }.map { it.name })
        assertNull(section.field("botToken")?.refusal(BOT_TOKEN))
        assertEquals("refused", section.field("botToken")?.refusal(BOT_TOKEN.reversed()))
        assertNull(section.field("applicationId")?.refusal(APPLICATION_ID))
        assertTrue(section.field("applicationId")?.refusal("x") != null)
        assertNull(section.field("guildId")?.refusal(GUILD_ID))
        assertTrue(section.field("guildId")?.refusal("x") != null)
    }

    @Test
    fun theConsolesOriginIsTheConfiguredOneWithoutItsSlashElseTheDevelopmentOne() {
        assertEquals("https://console.example", DiscordTexts.consoleUrl(" https://console.example/ "))
        assertEquals(DiscordTexts.DEFAULT_CONSOLE_URL, DiscordTexts.consoleUrl(null))
        assertEquals(DiscordTexts.DEFAULT_CONSOLE_URL, DiscordTexts.consoleUrl("  "))
    }

    @Test
    fun theApplicationIsConfiguredOnlyWithItsFiveFieldsAndNeverShowsItsSecrets() = runTest {
        val app = apps.current()
        assertEquals(BOT_TOKEN, app?.botToken)
        assertEquals(CLIENT_SECRET, app?.clientSecret)
        assertEquals(GUILD_ID, app?.guildId)
        assertEquals("DiscordApp($APPLICATION_ID, ***)", app.toString())
        settings.configured = false
        assertNull(apps.current())
        val all = mapOf(
            DiscordSettings.BOT_TOKEN to BOT_TOKEN, DiscordSettings.APPLICATION_ID to APPLICATION_ID,
            DiscordSettings.PUBLIC_KEY to PUBLIC_KEY, DiscordSettings.CLIENT_SECRET to CLIENT_SECRET,
            DiscordSettings.GUILD_ID to GUILD_ID,
        )
        for (field in all.keys) {
            val partial = object : InstanceSettings {
                override suspend fun read(section: String) = SettingsValues(all + (field to " "))
            }
            assertNull(DiscordApps(partial).current(), "Without $field, no application")
        }
    }

    @Test
    fun aNotificationIsItsTitleItsTextAndALinkWithAPersonsTextEscaped() {
        val notification = Notification(
            Uuid.random(), org, bob, NotificationKind.WAITING, "Ship *it* @here", "Review [this](x)", "s-1", "w-1",
            "wait:w-1", now, 1,
        )
        assertEquals(
            "**Ship \\*it\\* \\@here**\nReview \\[this\\]\\(x\\)\n<https://console.ostrom.test/waits/w-1>",
            texts.notification(notification).content,
        )
        val session = notification.copy(waitId = null, body = "")
        assertEquals(
            "**Ship \\*it\\* \\@here**\n<https://console.ostrom.test/sessions/s-1>",
            texts.notification(session).content,
        )
        assertEquals("**Ship \\*it\\* \\@here**", texts.notification(session.copy(sessionId = null)).content)
        assertEquals(
            "**Ship \\*it\\* \\@here**\n<https://console.ostrom.test/waits/w-1_x9>",
            texts.notification(session.copy(waitId = "w-1_x/../9")).content,
            "Only an id's letters, digits, dashes and underscores go in the link",
        )
        assertNull(texts.notification(notification).components)
        val long = texts.notification(notification.copy(body = "a".repeat(3000))).content
        assertEquals(DiscordTexts.MAX_CONTENT, long.length)
        assertTrue(long.endsWith("…"))
        val emoji = DiscordTexts.fit("a".repeat(DiscordTexts.MAX_CONTENT - 2) + "\uD83D\uDE00".repeat(10))
        assertEquals("a".repeat(DiscordTexts.MAX_CONTENT - 2) + "…", emoji, "Never half of a surrogate pair")
    }

    @Test
    fun aYesRequestOpensTheConsoleToApproveAndCarriesItsRequestToReject() {
        val approval = Uuid.random()
        val message = texts.approval(org, approval, "ana asks for your yes", "Push to main", "https://c/yes/$approval")
        val buttons = message.components!![0].jsonObject["components"]!!.jsonArray
        val approve = buttons[0].jsonObject
        val reject = buttons[1].jsonObject
        assertEquals("https://c/yes/$approval", approve["url"]?.jsonPrimitive?.content)
        assertEquals("ostrom_reject:$org:$approval", reject["custom_id"]?.jsonPrimitive?.content)
        assertEquals(DiscordTexts.rejectId(org, approval), reject["custom_id"]?.jsonPrimitive?.content)
        assertEquals(
            "**ana asks for your yes**\nPush to main\n-# A yes is given in Ostrom, after proving it is you.",
            message.content,
        )
        val bodiless = texts.approval(org, approval, "ana asks", "", "https://c")
        assertEquals("**ana asks**\n-# A yes is given in Ostrom, after proving it is you.", bodiless.content)
        val closed = texts.decided("Push to main", "Rejected by bob.")
        assertEquals("**Push to main**\nRejected by bob.", closed.content)
        assertEquals(0, closed.components!!.size, "No buttons once decided")
        assertTrue("**bob**" in texts.linked("bob", "Acme").content)
    }

    @Test
    fun aStartedLinkIsDiscordsAuthorizationWithAStateNamingTheOrganization() = runTest {
        val url = Url(start(org, bob, SESSION))
        assertEquals("https://discord.com/oauth2/authorize", url.toString().substringBefore('?'))
        assertEquals("code", url.parameters["response_type"])
        assertEquals(APPLICATION_ID, url.parameters["client_id"])
        assertEquals("identify", url.parameters["scope"])
        assertEquals(REDIRECT, url.parameters["redirect_uri"])
        assertEquals("consent", url.parameters["prompt"])
        val state = url.parameters["state"]!!
        assertEquals(org.toString(), state.substringBefore('.'))
        assertEquals(43, state.substringAfter('.').length)
        val attempt = attempts.attempts.values.single()
        assertEquals(sha256Hex(state), attempt.stateHash)
        assertEquals(sha256Hex(SESSION), attempt.binding)
        assertEquals(now + 10.minutes, attempt.expiresAt)
        started()
        assertEquals(1, attempts.attempts.size, "One unfinished link per member")
        settings.configured = false
        assertFailsWith<DiscordNotConfiguredException> { start(org, bob, SESSION) }
    }

    @Test
    fun aMemberIsLinkedToTheDiscordAccountTheySignedInWithAndToldInDiscord() = runTest {
        val state = started()
        val linked = complete()(state, "code-$BOB_ID", null, SESSION)
        assertEquals(DiscordLink(bob.toString(), true, true, BOB_ID, "bob", now.toString()), linked)
        assertEquals(listOf(Triple(apps.current()!!.applicationId, "code-$BOB_ID", REDIRECT)), api.exchanged.map {
            Triple(it.first.applicationId, it.second, it.third)
        })
        assertEquals(listOf("DM-$BOB_ID"), api.posted.map { it.first }, "The Discord account is told")
        assertEquals("DM-$BOB_ID", links.links[org to bob]?.dmChannelId, "Its channel is kept")
        assertEquals(linked, GetDiscordLinkUseCaseImpl(apps, links)(org, bob))
        assertTrue(attempts.attempts.isEmpty(), "Single-use")
        assertFailsWith<DiscordLinkNotFoundException> { complete()(state, "code-$BOB_ID", null, SESSION) }

        api.postFailure = DiscordCallException("discord_50007", retryable = false)
        assertEquals(linked, complete()(started(), "code-$BOB_ID", null, SESSION), "The message is best effort")
        api.postFailure = null
        api.openFailure = DiscordCallException("discord_unavailable", retryable = true)
        assertEquals(linked, complete()(started(), "code-$BOB_ID", null, SESSION))
        api.openFailure = null

        val carol = Uuid.random()
        links.links[org to carol] = StoredDiscordLink(carol, BOB_ID, "bob", now)
        links.links.remove(org to bob)
        assertFailsWith<DiscordUserTakenException> { complete()(started(), "code-$BOB_ID", null, SESSION) }
        links.links.remove(org to carol)
        complete()(started(), "code-$BOB_ID", null, SESSION)
        complete()(started(), "code-$BOB_ID", null, SESSION)
        UnlinkDiscordUseCaseImpl(links)(org, bob)
        UnlinkDiscordUseCaseImpl(links)(org, bob)
        assertEquals(DiscordLink(bob.toString(), true, false), GetDiscordLinkUseCaseImpl(apps, links)(org, bob))
    }

    @Test
    fun aCallbackWithoutItsAttemptOrFromAnotherBrowserLinksNothing() = runTest {
        val code = "code-$BOB_ID"
        val random = "a".repeat(43)
        for (state in listOf(null, "", "not-a-state", "${"-".repeat(36)}.$random", "$org.$random")) {
            assertFailsWith<DiscordLinkNotFoundException>(state) { complete()(state, code, null, SESSION) }
        }
        val state = started()
        assertFailsWith<DiscordLinkBrowserMismatchException> { complete()(state, code, null, "another") }
        assertFailsWith<DiscordLinkBrowserMismatchException> { complete()(state, code, null, null) }
        assertEquals(1, attempts.attempts.size, "Left for the browser that started it")

        now += 11.minutes
        assertFailsWith<DiscordLinkNotFoundException> { complete()(state, code, null, SESSION) }
        assertTrue(attempts.attempts.isEmpty(), "An expired attempt is used up all the same")
        now -= 11.minutes

        attempts.raced = true
        assertFailsWith<DiscordLinkNotFoundException> { complete()(started(), code, null, SESSION) }
        attempts.raced = false
        assertTrue(links.links.isEmpty())
    }

    @Test
    fun aDeclinedOrRefusedSignInIsDeniedAndDiscordDownIsUnreachable() = runTest {
        assertFailsWith<DiscordLinkDeniedException> { complete()(started(), null, "access_denied", SESSION) }
        assertFailsWith<DiscordLinkNotFoundException> { complete()(started(), null, null, SESSION) }
        assertFailsWith<DiscordLinkNotFoundException> { complete()(started(), "bad code!", null, SESSION) }
        api.exchangeFailure = DiscordCallException("discord_http_400", retryable = false)
        assertFailsWith<DiscordLinkDeniedException> { complete()(started(), "code-$BOB_ID", null, SESSION) }
        api.exchangeFailure = DiscordCallException("discord_unreachable", retryable = true)
        assertFailsWith<DiscordUnreachableException> { complete()(started(), "code-$BOB_ID", null, SESSION) }
        api.exchangeFailure = null
        api.guildFailure = DiscordCallException("discord_unavailable", retryable = true)
        assertFailsWith<DiscordUnreachableException> { complete()(started(), "code-$BOB_ID", null, SESSION) }
        api.guildFailure = DiscordCallException("discord_50001", retryable = false)
        assertFailsWith<DiscordNotConfiguredException>("A wrong server id is the administrator's") {
            complete()(started(), "code-$BOB_ID", null, SESSION)
        }
        api.guildFailure = null
        assertFailsWith<DiscordNotInGuildException> { complete()(started(), "code-$EVE_ID", null, SESSION) }
        val state = started()
        people = MapDiscordPeople(emptyMap())
        assertFailsWith<DiscordLinkNotFoundException>("The member left") {
            complete()(state, "code-$BOB_ID", null, SESSION)
        }
        people = MapDiscordPeople(mapOf(bob to DiscordMember(bob, "bob", OrgRole.MEMBER, "Acme")))
        val late = started()
        settings.configured = false
        assertFailsWith<DiscordNotConfiguredException> { complete()(late, "code-$BOB_ID", null, SESSION) }
        assertEquals(DiscordLink(bob.toString(), false, false), GetDiscordLinkUseCaseImpl(apps, links)(org, bob))
        assertTrue(links.links.isEmpty())
    }

    @Test
    fun aCancelledConfirmationIsNotMistakenForAFailedOne() = runTest {
        val cancelling = object : DiscordApi by api {
            override suspend fun openDm(botToken: String, userId: String): String = throw CancellationException("Stop")
        }
        val complete = CompleteDiscordLinkUseCaseImpl(apps, cancelling, attempts, people, links, texts, REDIRECT, clock)
        assertFailsWith<CancellationException> { complete(started(), "code-$BOB_ID", null, SESSION) }
    }

    private companion object {
        const val REDIRECT = "https://ostrom.test/api/v1/discord/link/callback"
        const val SESSION = "session-1"
        const val BOB_ID = "300000000000000003"
        const val EVE_ID = "300000000000000004"
    }
}
