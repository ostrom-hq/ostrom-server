// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.integrations.slack.domain.SlackCallException
import dev.ostrom.server.integrations.slack.domain.SlackMessages
import dev.ostrom.server.integrations.slack.domain.SlackText
import dev.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import dev.ostrom.server.integrations.slack.infrastructure.SlackSettings
import dev.ostrom.server.integrations.slack.infrastructure.SlackSignature
import dev.ostrom.server.integrations.slack.presentation.clickOf
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Slack's signature, the settings, the Web API client and the reading of an interaction. */
class SlackInfrastructureTest {
    private val clock = SettableClock()
    private val secret = "signing-secret"
    private val calls = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("unset") }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(token: String? = "xoxb-test"): HttpSlackApi {
        val engine = MockEngine { request ->
            calls += request
            handler(request)
        }
        return HttpSlackApi(HttpClient(engine) { expectSuccess = false }, "https://slack.test/api", token)
    }

    private val message = SlackText("hi", JsonArray(emptyList()))

    @Test
    fun aBodySignedWithTheSecretRecentlyIsVerified() = runTest {
        val signature = SlackSignature(secret, clock)
        val ts = clock.now.epochSeconds.toString()
        val body = "payload=%7B%7D".encodeToByteArray()
        val signed = slackSign(secret, ts, body)
        assertTrue(signature.verify(body, ts, signed))
        assertFalse(signature.verify("payload=x".encodeToByteArray(), ts, signed), "another body")
        assertFalse(signature.verify(body, ts, signed.dropLast(1)), "a shorter signature")
        assertFalse(signature.verify(body, ts, signed + "0"), "a longer signature")
        assertFalse(SlackSignature("other", clock).verify(body, ts, signed), "another secret")
        assertFalse(SlackSignature(null, clock).verify(body, ts, signed), "no secret on this server")
        assertFalse(signature.verify(body, null, signed), "no timestamp")
        assertFalse(signature.verify(body, "soon", signed), "a timestamp that is no number")
        assertFalse(signature.verify(body, ts, null), "no signature")
        clock.now += 6.minutes
        assertFalse(signature.verify(body, ts, signed), "replayed after five minutes")
    }

    @Test
    fun theSettingsComeFromTheConfigurationAndNeverShowTheSecrets() {
        val read = SlackSettings.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_SLACK_BOT_TOKEN" to " xoxb-1 ",
                    "OSTROM_SLACK_SIGNING_SECRET" to "s3cr3t",
                    "OSTROM_SLACK_API" to "https://slack.test/api/",
                    "OSTROM_WEB_ORIGIN" to "https://console.example/",
                ),
            ),
        )
        assertEquals(SlackSettings("xoxb-1", "s3cr3t", "https://slack.test/api", "https://console.example"), read)
        assertFalse(read.toString().contains("xoxb-1") || read.toString().contains("s3cr3t"))
        val defaults = SlackSettings.from(MapConfigSource(mapOf("OSTROM_SLACK_BOT_TOKEN" to "  ")))
        assertEquals(SlackSettings(null, null), defaults, "a blank value counts as absent")
    }

    @Test
    fun theClientOpensADirectMessagePostsAndUpdatesWithTheBotToken() = runTest {
        handler = { request ->
            when (request.url.encodedPath) {
                "/api/conversations.open" -> json("""{"ok":true,"channel":{"id":"D1"}}""")
                "/api/chat.postMessage" -> json("""{"ok":true,"ts":"171.1","extra":1}""")
                else -> json("""{"ok":true}""")
            }
        }
        val api = api()
        assertTrue(api.configured)
        assertEquals("D1", api.openDirectMessage("U1"))
        assertEquals("171.1", api.post("D1", message))
        api.update("D1", "171.1", message)
        assertTrue(calls.all { it.headers[HttpHeaders.Authorization] == "Bearer xoxb-test" })
        val update = Json.parseToJsonElement((calls.last().body as TextContent).text).jsonObject
        assertEquals("171.1", update["ts"]!!.jsonPrimitive.content)
        assertEquals("D1", update["channel"]!!.jsonPrimitive.content)
    }

    // A lambda inside `to` gets no expected type: the parameter gives it its receiver.
    private fun case(says: String, respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        respond to says

    @Test
    fun everyRefusalOfSlackIsASlackCallException() = runTest {
        val cases = listOf(
            case("channel_not_found") { json("""{"ok":false,"error":"channel_not_found"}""") },
            case("429") { json("{}", HttpStatusCode.TooManyRequests) },
            case("unexpectedly (502)") { json("<html>", HttpStatusCode.BadGateway) },
            case("no direct message") { json("""{"ok":true}""") },
            case("did not answer") { throw IllegalStateException("connection refused") },
        )
        for ((respond, says) in cases) {
            handler = respond
            val error = assertFailsWith<SlackCallException> { api().openDirectMessage("U1") }
            assertTrue(error.message!!.contains(says), error.message)
        }
        handler = { json("""{"ok":true}""") }
        assertTrue(assertFailsWith<SlackCallException> { api().post("D1", message) }.message!!.contains("no message"))
        handler = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { api().update("D1", "1", message) }
    }

    @Test
    fun anUnconfiguredClientCallsNothing() = runTest {
        val api = api(token = null)
        assertFalse(api.configured)
        assertFailsWith<SlackCallException> { api.update("D1", "1", message) }
        assertTrue(calls.isEmpty())
    }

    private fun interaction(json: String) = "payload=" + json.encodeURLParameter()

    @Test
    fun aButtonOfARequestIsReadFromTheInteraction() {
        val request = Uuid.random()
        val body = """{"type":"block_actions","team":{"id":"T1"},"user":{"id":"U1"},""" +
            """"actions":[{"action_id":"${SlackMessages.NO}","value":"$request:abc:d"}],""" +
            """"container":{"channel_id":"D1","message_ts":"171.1"},"other":true}"""
        val click = clickOf(interaction(body))!!
        assertEquals(listOf("T1", "U1", "D1", "171.1"), listOf(click.teamId, click.userId, click.channel, click.ts))
        assertEquals(request, click.requestId)
        assertEquals("abc:d", click.shownDigest)
        assertFalse(click.yes)
        assertTrue(clickOf("a=1&" + interaction(body.replace(SlackMessages.NO, SlackMessages.YES)))!!.yes)
    }

    @Test
    fun anythingButAButtonOfARequestIsIgnored() {
        val request = Uuid.random()
        fun body(type: String = "block_actions", action: String = SlackMessages.YES, value: String = "$request:d") =
            """{"type":"$type","actions":[{"action_id":"$action","value":"$value"}],"container":{"channel_id":"D1"}}"""
        for (ignored in listOf(
            "",
            "token=1",
            "payload=%ZZ",
            interaction("not json"),
            interaction(body(type = "view_submission")),
            interaction(body(action = "other")),
            interaction(body(value = "no-separator")),
            interaction(body(value = "not-a-uuid:d")),
            interaction("""{"type":"block_actions","actions":[{"action_id":"ostrom_yes","value":"$request:d"}]}"""),
        )) {
            assertNull(clickOf(ignored), ignored)
        }
    }
}

/** Signs [body] the way Slack does. */
suspend fun slackSign(secret: String, timestamp: String, body: ByteArray): String {
    val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
        .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
    val mac = key.signatureGenerator().generateSignature("v0:$timestamp:".encodeToByteArray() + body)
    return "v0=" + mac.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
