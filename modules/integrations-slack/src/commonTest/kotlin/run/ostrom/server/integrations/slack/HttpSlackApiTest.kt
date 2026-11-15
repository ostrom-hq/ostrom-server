// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackMessage
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.SlackUser
import run.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slack's Web API: its `ok`, its error codes and its HTTP failures, never its text nor the token. */
class HttpSlackApiTest {
    private val calls = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private fun api(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpSlackApi {
        val engine = MockEngine { request ->
            calls += request
            bodies += request.body.toByteArray().decodeToString()
            answer(request)
        }
        return HttpSlackApi(HttpClient(engine), "https://slack.test/api")
    }

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun aMessageIsPostedToThePersonWithTheTokenAndUpdatedWhereItWas() = runTest {
        val slack = api { json("""{"ok":true,"channel":"D0BOB","ts":"1.2"}""") }
        val posted = slack.postMessage(BOT_TOKEN, "U0BOB", SlackMessage("Hello <you>"))
        assertEquals(SlackPosted("D0BOB", "1.2"), posted)
        slack.updateMessage(BOT_TOKEN, posted, SlackMessage("Done"))
        slack.deleteMessage(BOT_TOKEN, posted)
        assertEquals(
            listOf(
                "https://slack.test/api/chat.postMessage",
                "https://slack.test/api/chat.update",
                "https://slack.test/api/chat.delete",
            ),
            calls.map { it.url.toString() },
        )
        assertTrue(""""channel":"D0BOB"""" in bodies[2] && """"ts":"1.2"""" in bodies[2], bodies[2])
        assertEquals("Bearer $BOT_TOKEN", calls[0].headers[HttpHeaders.Authorization])
        assertTrue(""""channel":"U0BOB"""" in bodies[0], bodies[0])
        assertTrue(""""ts":"1.2"""" in bodies[1], bodies[1])
    }

    @Test
    fun aPersonIsLookedUpByAddressAndAnUnknownOneIsNone() = runTest {
        val user = """{"ok":true,"user":{"id":"U0BOB","deleted":false,"is_bot":false}}"""
        val found = api { json(user) }.lookupByEmail(BOT_TOKEN, "b@x")
        assertEquals(SlackUser("U0BOB", deleted = false, bot = false), found)
        val bot = """{"ok":true,"user":{"id":"U0BOT","is_app_user":true}}"""
        assertEquals(SlackUser("U0BOT", deleted = false, bot = true), api { json(bot) }.lookupByEmail(BOT_TOKEN, "b@x"))
        assertNull(api { json("""{"ok":false,"error":"users_not_found"}""") }.lookupByEmail(BOT_TOKEN, "n@x"))
        val refused = api { json("""{"ok":false,"error":"missing_scope"}""") }
        val scope = assertFailsWith<SlackCallException> { refused.lookupByEmail(BOT_TOKEN, "x") }
        assertEquals("missing_scope", scope.code)
        val noId = api { json("""{"ok":true,"user":{"deleted":true}}""") }
        val idless = assertFailsWith<SlackCallException> { noId.lookupByEmail(BOT_TOKEN, "x") }
        assertEquals("slack_malformed", idless.code)
        val robot = """{"ok":true,"user":{"id":"U0BOT","is_bot":true}}"""
        val asBot = api { json(robot) }.lookupByEmail(BOT_TOKEN, "b@x")
        assertEquals(SlackUser("U0BOT", deleted = false, bot = true), asBot)
        for (id in listOf("5", "{}")) {
            val odd = api { json("""{"ok":true,"user":{"id":$id}}""") }
            val refused = assertFailsWith<SlackCallException> { odd.lookupByEmail(BOT_TOKEN, "x") }
            assertEquals("slack_malformed", refused.code)
        }
        val noUser = api { json("""{"ok":true}""") }
        val userless = assertFailsWith<SlackCallException> { noUser.lookupByEmail(BOT_TOKEN, "x") }
        assertEquals("slack_malformed", userless.code)
    }

    @Test
    fun aTokenSlackRefusesIsNamedByItsCode() = runTest {
        assertNull(api { json("""{"ok":true}""") }.authTest(BOT_TOKEN))
        assertEquals("invalid_auth", api { json("""{"ok":false,"error":"invalid_auth"}""") }.authTest(BOT_TOKEN))
        assertEquals("slack_error", api { json("""{"ok":false}""") }.authTest(BOT_TOKEN), "No code")
        assertEquals("slack_error", api { json("""{"ok":false,"error":"Not A Code!"}""") }.authTest(BOT_TOKEN))
    }

    @Test
    fun slackFailingOrAskingToSlowDownIsRetryableAndARefusalIsNot() = runTest {
        suspend fun failure(slack: HttpSlackApi) =
            assertFailsWith<SlackCallException> { slack.postMessage(BOT_TOKEN, "U0BOB", SlackMessage("x")) }
        val limited = failure(api { json("{}", HttpStatusCode.TooManyRequests) })
        assertEquals("ratelimited" to true, limited.code to limited.retryable)
        val down = failure(api { json("{}", HttpStatusCode.BadGateway) })
        assertEquals("slack_unavailable" to true, down.code to down.retryable)
        val unreachable = failure(api { error("Connection refused") })
        assertEquals("slack_unreachable" to true, unreachable.code to unreachable.retryable)
        val garbled = failure(api { json("<html>") })
        assertEquals("slack_malformed" to true, garbled.code to garbled.retryable)
        val noTs = failure(api { json("""{"ok":true,"channel":"D0BOB"}""") })
        assertEquals("slack_malformed", noTs.code)
        val noChannel = failure(api { json("""{"ok":true,"ts":"1.2"}""") })
        assertEquals("slack_malformed", noChannel.code)
        val notAnObject = failure(api { json("[]") })
        assertEquals("slack_malformed", notAnObject.code)
        val inactive = failure(api { json("""{"ok":false,"error":"account_inactive"}""") })
        assertEquals("account_inactive" to false, inactive.code to inactive.retryable)
        val transient = failure(api { json("""{"ok":false,"error":"internal_error"}""") })
        assertTrue(transient.retryable)
    }
}
