// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.notifications.domain.EXPO_ACCESS_TOKEN_FIELD
import run.ostrom.server.notifications.domain.EXPO_PUSH_URL_FIELD
import run.ostrom.server.notifications.domain.PUSH_SETTINGS
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushNotSentException
import run.ostrom.server.notifications.infrastructure.ExpoPushSender
import run.ostrom.server.notifications.infrastructure.MAX_BATCH
import run.ostrom.server.notifications.infrastructure.NotificationSettings
import run.ostrom.server.notifications.infrastructure.startLevel
import run.ostrom.server.notifications.infrastructure.ticketError
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpoPushSenderTest {
    private val url = "https://expo.test/--/api/v2/push/send"
    private val message = PushMessage(
        testPhone("ExponentPushToken[bob-phone-1]"),
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

        ExpoPushSender(http, settings("expo-access")).send(listOf(message))

        val request = requests.single()
        assertEquals(url, request.url.toString())
        assertEquals("Bearer expo-access", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, (request.body as OutgoingContent).contentType?.withoutParameters())
        assertEquals(
            Json.parseToJsonElement(
                """[{"to":"ExponentPushToken[bob-phone-1]","title":"ana asks for your yes",""" +
                    """"body":"Push to main · acme/app","sound":"default","channelId":"requests",""" +
                    """"data":{"kind":"approval","approvalId":"a1"}}]""",
            ),
            body(request),
        )
    }

    @Test
    fun withoutAccessTokenNoneIsSentAndAHundredMessagesGoPerRequest() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("{}") })

        val phones = List(MAX_BATCH + 1) { message.copy(device = testPhone("ExponentPushToken[p$it]")) }
        ExpoPushSender(http, settings(null)).send(phones)

        assertEquals(listOf(MAX_BATCH, 1), requests.map { body(it).size })
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == null })
        assertEquals(100, MAX_BATCH)
    }

    @Test
    fun aRefusalOrNoAnswerIsAPushNotSentThatNamesNoToken() = runTest {
        val refused = HttpClient(MockEngine { respond("{}", HttpStatusCode.TooManyRequests) }) { expectSuccess = false }
        val error = assertFailsWith<PushNotSentException> {
            ExpoPushSender(refused, settings(null)).send(listOf(message))
        }
        assertEquals("Expo refused (429).", error.message)

        val down = HttpClient(MockEngine { error("network down: ${it.url}") })
        val silent = assertFailsWith<PushNotSentException> {
            ExpoPushSender(down, settings(null)).send(listOf(message))
        }
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
            ExpoPushSender(unregistered, settings(null)).send(listOf(message))
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
        assertFailsWith<CancellationException> { ExpoPushSender(cancelled, settings(null)).send(listOf(message)) }
    }

    @Test
    fun withNothingSetInTheConsoleTheirPushGoesToExposOwnEndpoint() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("{}") })
        val read = mutableListOf<String>()

        ExpoPushSender(http, { section -> read += section; SettingsValues.EMPTY }).send(listOf(message))

        assertEquals(listOf("push"), read, "The push section is read at each send")
        assertEquals("https://exp.host/--/api/v2/push/send", requests.single().url.toString())
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun aChangeSavedInTheConsoleAppliesToTheNextPush() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("{}") })
        var stored = mapOf("expoPushUrl" to url)
        val sender = ExpoPushSender(http, { SettingsValues(stored) })

        sender.send(listOf(message))
        stored = mapOf("expoPushUrl" to "https://relay.test/push", "expoAccessToken" to "rotated")
        sender.send(listOf(message))

        assertEquals(listOf(url, "https://relay.test/push"), requests.map { it.url.toString() })
        assertEquals(listOf(null, "Bearer rotated"), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun storedValuesAreTrimmedAndABlankOneIsUnset() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("{}") })
        var stored = mapOf("expoPushUrl" to " $url\n", "expoAccessToken" to " expo-access\n")
        val sender = ExpoPushSender(http, { SettingsValues(stored) })

        sender.send(listOf(message))
        stored = mapOf("expoPushUrl" to "  ", "expoAccessToken" to " ")
        sender.send(listOf(message))

        assertEquals(listOf(url, "https://exp.host/--/api/v2/push/send"), requests.map { it.url.toString() })
        assertEquals(listOf("Bearer expo-access", null), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun theConsolesPushSettingsHoldExposEndpointAndItsSealedToken() {
        assertEquals("push", PUSH_SETTINGS.name)
        val endpoint = assertNotNull(PUSH_SETTINGS.field(EXPO_PUSH_URL_FIELD))
        val token = assertNotNull(PUSH_SETTINGS.field(EXPO_ACCESS_TOKEN_FIELD))
        assertEquals("expoPushUrl", endpoint.name)
        assertEquals("expoAccessToken", token.name)
        assertEquals("OSTROM_EXPO_PUSH_URL", endpoint.legacyVariable)
        assertFalse(endpoint.secret)
        assertEquals("OSTROM_EXPO_ACCESS_TOKEN", token.legacyVariable)
        assertTrue(token.secret)
        assertNull(endpoint.check("http://127.0.0.1:4621/--/api/v2/push/send"))
        assertNull(endpoint.check("http://localhost/push"))
        assertNull(endpoint.check("https://exp.host"))
        assertNull(endpoint.check(" https://exp.host/--/api/v2/push/send\n"), "an imported variable's newline")
        val refusal = "Expo's push endpoint is an https URL, or http to localhost or 127.0.0.1, without credentials."
        for (refused in listOf(
            "exp.host/--/api/v2/push/send",
            "ftp://exp.host",
            "http://exp.host/--/api/v2/push/send",
            "http://169.254.169.254/latest",
            "https://user@exp.host/push",
            "http://localhost.evil.example/push",
        )) {
            assertEquals(refusal, endpoint.check(refused), refused)
        }
        assertNull(token.check("any-token"))
    }

    @Test
    fun theWebOriginAndPublicUrlAreReadFromTheConfiguration() {
        val defaults = NotificationSettings.from(config(emptyMap()))
        assertEquals(NotificationSettings(), defaults)
        assertEquals("http://localhost:5173", defaults.webOrigin)
        assertEquals("", defaults.publicUrl, "No public URL set")
        val set = NotificationSettings.from(
            config(
                mapOf(
                    "OSTROM_WEB_ORIGIN" to " https://console.ostrom.test/ ",
                    "OSTROM_PUBLIC_URL" to "https://ostrom.test/",
                ),
            ),
        )
        assertEquals(NotificationSettings("https://console.ostrom.test", "https://ostrom.test"), set)
    }

    private fun settings(token: String?) = InstanceSettings { section ->
        assertEquals("push", section)
        SettingsValues(
            buildMap {
                put("expoPushUrl", url)
                token?.let { put("expoAccessToken", it) }
            },
        )
    }

    private fun config(values: Map<String, String>) = object : ConfigSource {
        override fun get(key: String): String? = values[key]
    }
}
