// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
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

    private fun api(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpSlackApi {
        val engine = MockEngine { request ->
            calls += request
            answer(request)
        }
        return HttpSlackApi(HttpClient(engine), "https://slack.test/api")
    }

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.text(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> ""
    }

    @Test
    fun aMessageIsPostedToThePersonWithTheTokenAndUpdatedWhereItWas() = runTest {
        val slack = api { json("""{"ok":true,"channel":"D0BOB","ts":"1.2"}""") }
        val posted = slack.postMessage(BOT_TOKEN, "U0BOB", SlackMessage("Hello <you>"))
        assertEquals(SlackPosted("D0BOB", "1.2"), posted)
        slack.updateMessage(BOT_TOKEN, posted, SlackMessage("Done"))
        assertEquals(
            listOf("https://slack.test/api/chat.postMessage", "https://slack.test/api/chat.update"),
            calls.map { it.url.toString() },
        )
        assertEquals("Bearer $BOT_TOKEN", calls[0].headers[HttpHeaders.Authorization])
        assertTrue(""""channel":"U0BOB"""" in calls[0].text(), calls[0].text())
        assertTrue(""""ts":"1.2"""" in calls[1].text(), calls[1].text())
    }

    @Test
    fun aPersonIsLookedUpByAddressAndAnUnknownOneIsNone() = runTest {
        val user = """{"ok":true,"user":{"id":"U0BOB","deleted":false,"is_bot":false}}"""
        assertEquals(SlackUser("U0BOB", deleted = false, bot = false), api { json(user) }.lookupByEmail(BOT_TOKEN, "b@x"))
        val bot = """{"ok":true,"user":{"id":"U0BOT","is_app_user":true}}"""
        assertEquals(SlackUser("U0BOT", deleted = false, bot = true), api { json(bot) }.lookupByEmail(BOT_TOKEN, "b@x"))
        assertNull(api { json("""{"ok":false,"error":"users_not_found"}""") }.lookupByEmail(BOT_TOKEN, "n@x"))
        val noUser = api { json("""{"ok":true}""") }
        assertEquals("slack_malformed", assertFailsWith<SlackCallException> { noUser.lookupByEmail(BOT_TOKEN, "x") }.code)
    }

    @Test
    fun aTokenSlackRefusesIsNamedByItsCode() = runTest {
        assertNull(api { json("""{"ok":true}""") }.authTest(BOT_TOKEN))
        assertEquals("invalid_auth", api { json("""{"ok":false,"error":"invalid_auth"}""") }.authTest(BOT_TOKEN))
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
        val inactive = failure(api { json("""{"ok":false,"error":"account_inactive"}""") })
        assertEquals("account_inactive" to false, inactive.code to inactive.retryable)
        val transient = failure(api { json("""{"ok":false,"error":"internal_error"}""") })
        assertTrue(transient.retryable)
    }
}
