// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushNotSentException
import dev.ostrom.server.notifications.infrastructure.ExpoPushSender
import dev.ostrom.server.notifications.infrastructure.MAX_BATCH
import dev.ostrom.server.notifications.infrastructure.NotificationSettings
import dev.ostrom.server.notifications.infrastructure.startLevel
import dev.ostrom.server.notifications.infrastructure.ticketError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpoPushSenderTest {
    private val url = "https://expo.test/--/api/v2/push/send"
    private val message = PushMessage(
        "ExponentPushToken[bob-phone-1]",
        "ana asks for your yes",
        "Push to main · acme/app",
        mapOf("kind" to "approval", "approvalId" to "a1"),
    )

    private fun body(request: HttpRequestData): JsonArray =
        Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonArray

    @Test
    fun expoReceivesEachPhonesMessageWithTheAccessToken() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("""{"data":[{"status":"ok","id":"t1"}]}""") })

        ExpoPushSender(http, url, "expo-access").send(listOf(message))

        val request = requests.single()
        assertEquals(url, request.url.toString())
        assertEquals("Bearer expo-access", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, (request.body as OutgoingContent).contentType?.withoutParameters())
        assertEquals(
            Json.parseToJsonElement(
                """[{"to":"ExponentPushToken[bob-phone-1]","title":"ana asks for your yes",""" +
                    """"body":"Push to main · acme/app","sound":"default","channelId":"approvals",""" +
                    """"data":{"kind":"approval","approvalId":"a1"}}]""",
            ),
            body(request),
        )
    }

    @Test
    fun withoutAccessTokenNoneIsSentAndAHundredMessagesGoPerRequest() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("{}") })

        ExpoPushSender(http, url, null).send(List(MAX_BATCH + 1) { message.copy(to = "ExponentPushToken[p$it]") })

        assertEquals(listOf(MAX_BATCH, 1), requests.map { body(it).size })
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == null })
        assertEquals(100, MAX_BATCH)
    }

    @Test
    fun aRefusalOrNoAnswerIsAPushNotSentThatNamesNoToken() = runTest {
        val refused = HttpClient(MockEngine { respond("{}", HttpStatusCode.TooManyRequests) }) { expectSuccess = false }
        val error = assertFailsWith<PushNotSentException> { ExpoPushSender(refused, url, null).send(listOf(message)) }
        assertEquals("Expo refused (429).", error.message)

        val down = HttpClient(MockEngine { error("network down: ${it.url}") })
        val silent = assertFailsWith<PushNotSentException> { ExpoPushSender(down, url, null).send(listOf(message)) }
        assertEquals("Expo did not answer.", silent.message)
        assertFalse(silent.toString().contains("bob-phone"))
    }

    @Test
    fun aTicketInErrorIsARefusalWithItsCode() = runTest {
        val unregistered = HttpClient(
            MockEngine {
                respond(
                    """{"data":[{"status":"error","message":"not a valid token",""" +
                        """"details":{"error":"DeviceNotRegistered"}}]}""",
                )
            },
        )
        val error = assertFailsWith<PushNotSentException> {
            ExpoPushSender(unregistered, url, null).send(listOf(message))
        }
        assertEquals("Expo refused a message (DeviceNotRegistered).", error.message)
        assertFalse(error.toString().contains("bob-phone"))
    }

    @Test
    fun ticketsAreReadForTheFirstErrorAndAnUnreadableAnswerIsNone() {
        assertNull(ticketError("""{"data":[{"status":"ok","id":"t1"}]}"""))
        assertNull(ticketError("{}"))
        assertNull(ticketError("not json"))
        assertNull(ticketError("""{"data":{"status":"error"}}"""), "A single ticket is never the answer to a list")
        assertEquals("error", ticketError("""{"data":[{"status":"ok"},{"status":"error"}]}"""))
        assertEquals("error", ticketError("""{"data":[{"status":"error","details":{}}]}"""))
        assertEquals(
            "MessageRateExceeded",
            ticketError("""{"data":[{"status":"error","details":{"error":"MessageRateExceeded"}},{"status":"ok"}]}"""),
        )
    }

    @Test
    fun anInstanceThatMailsLinksToTheDefaultOriginWarnsAtStart() {
        val default = NotificationSettings.from(config(emptyMap()))
        val set = NotificationSettings.from(config(mapOf("OSTROM_WEB_ORIGIN" to "https://console.acme.test")))
        assertEquals(LogLevel.WARN, default.startLevel(mailing = true))
        assertEquals(LogLevel.INFO, default.startLevel(mailing = false))
        assertEquals(LogLevel.INFO, set.startLevel(mailing = true))
    }

    @Test
    fun aCancellationStaysACancellation() = runTest {
        val cancelled = HttpClient(MockEngine { throw CancellationException("stop") })
        assertFailsWith<CancellationException> { ExpoPushSender(cancelled, url, null).send(listOf(message)) }
    }

    @Test
    fun theSettingsReadTheConfigurationAndNeverPrintTheToken() {
        val defaults = NotificationSettings.from(config(emptyMap()))
        assertEquals(NotificationSettings(), defaults)
        assertEquals("https://exp.host/--/api/v2/push/send", defaults.expoPushUrl)
        assertNull(defaults.expoAccessToken)
        assertEquals("http://localhost:5173", defaults.webOrigin)
        assertTrue(defaults.toString().contains("expoAccessToken=none"))

        val set = NotificationSettings.from(
            config(
                mapOf(
                    "OSTROM_EXPO_PUSH_URL" to " http://127.0.0.1:4621/--/api/v2/push/send ",
                    "OSTROM_EXPO_ACCESS_TOKEN" to "expo-secret",
                    "OSTROM_WEB_ORIGIN" to "https://console.ostrom.test/",
                ),
            ),
        )
        assertEquals(
            NotificationSettings(
                "http://127.0.0.1:4621/--/api/v2/push/send",
                "expo-secret",
                "https://console.ostrom.test",
            ),
            set,
        )
        assertFalse(set.toString().contains("expo-secret"), set.toString())
        assertTrue(set.toString().contains("expoAccessToken=set"))
        assertNull(NotificationSettings.from(config(mapOf("OSTROM_EXPO_ACCESS_TOKEN" to "  "))).expoAccessToken)
    }

    private fun config(values: Map<String, String>) = object : ConfigSource {
        override fun get(key: String): String? = values[key]
    }
}
