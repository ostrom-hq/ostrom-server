// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import run.ostrom.server.integrations.discord.domain.DiscordApp
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordMessage
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordUser
import run.ostrom.server.integrations.discord.infrastructure.HttpDiscordApi
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discord's HTTP API: its JSON error codes, its statuses and its rate limits, never its text nor a token. */
class HttpDiscordApiTest {
    private val calls = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private val pauses = mutableListOf<Long>()
    private val app = DiscordApp(BOT_TOKEN, APPLICATION_ID, PUBLIC_KEY, CLIENT_SECRET, GUILD_ID)

    private fun api(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpDiscordApi {
        val engine = MockEngine { request ->
            calls += request
            bodies += request.body.toByteArray().decodeToString()
            answer(request)
        }
        return HttpDiscordApi(HttpClient(engine), "https://discord.test/api") { pauses += it }
    }

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun theDefaultBaseIsDiscordsVersionTenAndAShortWaitIsReallyWaited() = runTest {
        assertEquals("https://discord.com/api/v10", HttpDiscordApi.DEFAULT_BASE_URL)
        var answers = 0
        val engine = MockEngine { request ->
            calls += request
            answers++
            if (answers == 1) {
                respond("""{"retry_after":0.01}""", HttpStatusCode.TooManyRequests)
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        HttpDiscordApi(HttpClient(engine)).deleteMessage(BOT_TOKEN, POSTED)
        assertEquals("https://discord.com/api/v10/channels/DM-1/messages/900000000000000001", calls[0].url.toString())
        assertEquals(2, calls.size)
    }

    @Test
    fun aMessageIsPostedInTheChannelWithTheBotTokenEditedAndDeletedWhereItWas() = runTest {
        val discord = api { request ->
            when (request.method) {
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> json("""{"id":"900000000000000001","channel_id":"DM-1"}""")
            }
        }
        val posted = discord.postMessage(BOT_TOKEN, "DM-1", DiscordMessage("Hello @everyone"))
        assertEquals(DiscordPosted("DM-1", "900000000000000001"), posted)
        discord.editMessage(BOT_TOKEN, posted, DiscordMessage("Done", JsonArray(emptyList())))
        discord.deleteMessage(BOT_TOKEN, posted)
        assertEquals(
            listOf(
                "POST https://discord.test/api/channels/DM-1/messages",
                "PATCH https://discord.test/api/channels/DM-1/messages/900000000000000001",
                "DELETE https://discord.test/api/channels/DM-1/messages/900000000000000001",
            ),
            calls.map { "${it.method.value} ${it.url}" },
        )
        assertEquals("Bot $BOT_TOKEN", calls[0].headers[HttpHeaders.Authorization])
        assertEquals(
            """{"content":"Hello @everyone","allowed_mentions":{"parse":[]}}""",
            bodies[0],
            "Nobody is ever mentioned",
        )
        assertEquals("""{"content":"Done","components":[],"allowed_mentions":{"parse":[]}}""", bodies[1])
    }

    @Test
    fun aDirectMessageChannelIsOpenedWithThePerson() = runTest {
        val discord = api { json("""{"id":"DM-7","type":1}""") }
        assertEquals("DM-7", discord.openDm(BOT_TOKEN, "300000000000000003"))
        assertEquals("https://discord.test/api/users/@me/channels", calls.single().url.toString())
        assertEquals("""{"recipient_id":"300000000000000003"}""", bodies.single())
        val noId = assertFailsWith<DiscordCallException> { api { json("""{"type":1}""") }.openDm(BOT_TOKEN, "1") }
        assertEquals("discord_malformed" to true, noId.code to noId.retryable)
        val empty = assertFailsWith<DiscordCallException> { api { respond("") }.openDm(BOT_TOKEN, "1") }
        assertEquals("discord_malformed", empty.code)
    }

    @Test
    fun theLinksCodeIsExchangedWithTheClientSecretAndThePersonRead() = runTest {
        val discord = api { request ->
            when (request.url.encodedPath) {
                "/api/oauth2/token" -> json("""{"access_token":"person-token","token_type":"Bearer"}""")
                else -> json("""{"id":"300000000000000003","username":"bob"}""")
            }
        }
        assertEquals("person-token", discord.exchangeCode(app, "the-code", "https://o.test/cb"))
        val basic = Base64.encode("$APPLICATION_ID:$CLIENT_SECRET".encodeToByteArray())
        assertEquals("Basic $basic", calls[0].headers[HttpHeaders.Authorization])
        val form = parseQueryString(bodies[0])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("https://o.test/cb", form["redirect_uri"])
        assertEquals(DiscordUser("300000000000000003", "bob"), discord.currentUser("person-token"))
        assertEquals("Bearer person-token", calls[1].headers[HttpHeaders.Authorization])

        val emptyAnswer = api { respond("") }
        assertEquals("discord_malformed", assertFailsWith<DiscordCallException> {
            emptyAnswer.exchangeCode(app, "c", "r")
        }.code)
        val noToken = api { json("""{"token_type":"Bearer"}""") }
        assertEquals("discord_malformed", assertFailsWith<DiscordCallException> {
            noToken.exchangeCode(app, "c", "r")
        }.code)
        val nameless = api { json("""{"id":"300000000000000003"}""") }.currentUser("t")
        assertEquals(DiscordUser("300000000000000003", ""), nameless)
        for (user in listOf("""{"username":"x"}""", """{"id":"12"}""", """{"id":12345678901234567}""", "[]")) {
            val odd = api { json(user) }
            assertEquals("discord_malformed", assertFailsWith<DiscordCallException>(user) { odd.currentUser("t") }.code)
        }
    }

    @Test
    fun aMemberOfTheServerIsKnownByItsMembershipAndAnUnknownMemberIsNone() = runTest {
        val member = api { json("""{"user":{"id":"300000000000000003"}}""") }
        assertTrue(member.inGuild(app, "300000000000000003"))
        assertEquals("https://discord.test/api/guilds/$GUILD_ID/members/300000000000000003", calls[0].url.toString())
        assertEquals("Bot $BOT_TOKEN", calls[0].headers[HttpHeaders.Authorization])
        val unknown = api { json("""{"message":"Unknown Member","code":10007}""", HttpStatusCode.NotFound) }
        assertFalse(unknown.inGuild(app, "300000000000000004"))
        val forbidden = api { json("""{"message":"Missing Access","code":50001}""", HttpStatusCode.Forbidden) }
        val refused = assertFailsWith<DiscordCallException> { forbidden.inGuild(app, "300000000000000004") }
        assertEquals("discord_50001" to false, refused.code to refused.retryable)
    }

    @Test
    fun aTokenDiscordRefusesIsNamedByItsCodeAndDiscordDownIsThrown() = runTest {
        assertNull(api { json("""{"id":"1","username":"ostrom","bot":true}""") }.checkBot(BOT_TOKEN))
        assertEquals("discord_http_401", api { json("""{"message":"401: Unauthorized","code":0}""",
            HttpStatusCode.Unauthorized) }.checkBot("bad"))
        val down = assertFailsWith<DiscordCallException> { api { json("", HttpStatusCode.BadGateway) }.checkBot("t") }
        assertEquals("discord_unavailable" to true, down.code to down.retryable)
    }

    @Test
    fun aRefusalKeepsDiscordsCodeElseItsStatusNeverItsText() = runTest {
        suspend fun refusal(text: String, status: HttpStatusCode = HttpStatusCode.BadRequest) =
            assertFailsWith<DiscordCallException>(text) { api { json(text, status) }.deleteMessage(BOT_TOKEN, POSTED) }
        val unknown = refusal("""{"message":"Unknown Message","code":10008}""", HttpStatusCode.NotFound)
        assertEquals("discord_10008" to false, unknown.code to unknown.retryable)
        assertEquals("discord_http_400", refusal("not json").code)
        assertEquals("discord_http_400", refusal("[]").code)
        assertEquals("discord_http_400", refusal("""{"code":"50007"}""").code)
        assertEquals("discord_http_400", refusal("""{"code":-1}""").code)
        assertEquals("discord_http_400", refusal("""{"code":1.5}""").code)
        assertEquals("discord_http_403", refusal("{}", HttpStatusCode.Forbidden).code)
    }

    @Test
    fun aShortRateLimitIsWaitedOnceAndALongOneIsRetryable() = runTest {
        var answers = 0
        val once = api {
            answers++
            if (answers == 1) {
                json("""{"message":"You are being rate limited.","retry_after":1.234,"global":false}""",
                    HttpStatusCode.TooManyRequests)
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        once.deleteMessage(BOT_TOKEN, POSTED)
        assertEquals(listOf(1234L), pauses, "Waited what Discord asked, once")
        assertEquals(2, calls.size)

        pauses.clear()
        val always = api { json("""{"retry_after":0.5}""", HttpStatusCode.TooManyRequests) }
        val limited = assertFailsWith<DiscordCallException> { always.deleteMessage(BOT_TOKEN, POSTED) }
        assertEquals("discord_rate_limited" to true, limited.code to limited.retryable)
        assertEquals(listOf(500L), pauses, "Never twice")

        pauses.clear()
        val long = api { json("""{"retry_after":30}""", HttpStatusCode.TooManyRequests) }
        assertEquals("discord_rate_limited", assertFailsWith<DiscordCallException> {
            long.deleteMessage(BOT_TOKEN, POSTED)
        }.code)
        assertEquals(emptyList(), pauses, "Too long to wait here: the queue retries")

        val header = api {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
        }
        assertFailsWith<DiscordCallException> { header.deleteMessage(BOT_TOKEN, POSTED) }
        assertEquals(listOf(2000L), pauses, "Its Retry-After header without a body")

        pauses.clear()
        for (odd in listOf("""{"retry_after":-1}""", """{"retry_after":"soon"}""", "nope")) {
            val wrong = api { json(odd, HttpStatusCode.TooManyRequests) }
            assertFailsWith<DiscordCallException>(odd) { wrong.deleteMessage(BOT_TOKEN, POSTED) }
        }
        assertEquals(emptyList(), pauses, "No usable wait: none")
    }

    @Test
    fun discordFailingUnreachableOrGarbledIsRetryable() = runTest {
        suspend fun failure(discord: HttpDiscordApi) =
            assertFailsWith<DiscordCallException> { discord.postMessage(BOT_TOKEN, "DM-1", DiscordMessage("x")) }
        val down = failure(api { json("{}", HttpStatusCode.ServiceUnavailable) })
        assertEquals("discord_unavailable" to true, down.code to down.retryable)
        val unreachable = failure(api { error("Connection refused") })
        assertEquals("discord_unreachable" to true, unreachable.code to unreachable.retryable)
        val garbled = failure(api { json("{not json") })
        assertEquals("discord_malformed" to true, garbled.code to garbled.retryable)
        val noId = failure(api { json("""{"channel_id":"DM-1"}""") })
        assertEquals("discord_malformed", noId.code)
        val empty = failure(api { respond("", HttpStatusCode.OK) })
        assertEquals("discord_malformed", empty.code, "A message is always answered")
    }

    @Test
    fun aCancelledCallIsNotMistakenForAnUnreachableDiscord() = runTest {
        val cancelled = api { throw CancellationException("Stop") }
        assertFailsWith<CancellationException> { cancelled.deleteMessage(BOT_TOKEN, POSTED) }
    }

    private companion object {
        val POSTED = DiscordPosted("DM-1", "900000000000000001")
    }
}
