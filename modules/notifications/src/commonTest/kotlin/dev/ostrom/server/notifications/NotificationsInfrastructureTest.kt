// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.notifications.domain.EmailMessage
import dev.ostrom.server.notifications.domain.EmailSender
import dev.ostrom.server.notifications.domain.NotificationCallException
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushSender
import dev.ostrom.server.notifications.infrastructure.ExpoPushSender
import dev.ostrom.server.notifications.infrastructure.NotificationSettings
import dev.ostrom.server.notifications.infrastructure.ResendEmailSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings, Expo's and Resend's clients, and the module's registration. */
class NotificationsInfrastructureTest {
    private val calls = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { ok() }

    private fun MockRequestHandleScope.ok(status: HttpStatusCode = HttpStatusCode.OK) =
        respond("""{"data":[]}""", status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun http() = HttpClient(
        MockEngine { request ->
            calls += request
            handler(request)
        },
    ) { expectSuccess = false }

    private fun bodyOf(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text)

    private val push = PushMessage(TOKEN, "lyes asks for your yes", "Merge", mapOf("kind" to "approval"))

    @Test
    fun theSettingsComeFromTheConfigurationAndNeverShowTheSecrets() {
        val read = NotificationSettings.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_EXPO_PUSH_URL" to "https://expo.test/push",
                    "OSTROM_EXPO_ACCESS_TOKEN" to " expo-secret ",
                    "OSTROM_RESEND_API" to "https://resend.test/",
                    "OSTROM_RESEND_API_KEY" to "re_secret",
                    "OSTROM_EMAIL_FROM" to "Ostrom <a@b.dev>",
                    "OSTROM_WEB_ORIGIN" to "https://console.example/",
                ),
            ),
        )
        val expected = NotificationSettings(
            "https://expo.test/push",
            "expo-secret",
            "https://resend.test",
            "re_secret",
            "Ostrom <a@b.dev>",
            "https://console.example",
        )
        assertEquals(expected, read)
        assertFalse(read.toString().contains("expo-secret") || read.toString().contains("re_secret"))
        val defaults = NotificationSettings.from(MapConfigSource(mapOf("OSTROM_RESEND_API_KEY" to " ")))
        assertEquals(NotificationSettings(), defaults, "a blank value counts as absent")
        assertTrue("expo=true, resend=true" in read.toString())
        assertTrue("expo=false, resend=false" in defaults.toString())
    }

    @Test
    fun expoReceivesEveryMessageInBatchesOfAHundredWithItsToken() = runTest {
        ExpoPushSender(http(), "https://expo.test/push", "expo-secret").send(List(101) { push })
        assertEquals(listOf(100, 1), calls.map { bodyOf(it).jsonArray.size })
        assertTrue(calls.all { it.headers[HttpHeaders.Authorization] == "Bearer expo-secret" })
        val first = bodyOf(calls.first()).jsonArray.first().jsonObject
        assertEquals(TOKEN, first.getValue("to").jsonPrimitive.content)
        assertEquals("approval", first.getValue("data").jsonObject.getValue("kind").jsonPrimitive.content)
        calls.clear()
        ExpoPushSender(http(), "https://expo.test/push", null).send(listOf(push))
        assertNull(calls.single().headers[HttpHeaders.Authorization], "no access token, no header")
    }

    @Test
    fun resendSendsTheEmailWithItsKey() = runTest {
        val resend = ResendEmailSender(http(), "https://resend.test", "re_secret", "Ostrom <a@b.dev>")
        assertTrue(resend.configured)
        resend.send(EmailMessage("rev@acme.dev", "Subject", "Text"))
        val call = calls.single()
        assertEquals("https://resend.test/emails", call.url.toString())
        assertEquals("Bearer re_secret", call.headers[HttpHeaders.Authorization])
        val body = bodyOf(call).jsonObject
        assertEquals("rev@acme.dev", body.getValue("to").jsonArray.single().jsonPrimitive.content)
        assertEquals("Ostrom <a@b.dev>", body.getValue("from").jsonPrimitive.content)
    }

    @Test
    fun aRefusalOrNoAnswerIsANotificationCallException() = runTest {
        val unconfigured = ResendEmailSender(http(), "https://resend.test", null, "x")
        assertFalse(unconfigured.configured)
        assertFailsWith<NotificationCallException> { unconfigured.send(EmailMessage("a", "b", "c")) }
        assertTrue(calls.isEmpty())
        handler = { ok(HttpStatusCode.TooManyRequests) }
        val refused = assertFailsWith<NotificationCallException> {
            ExpoPushSender(http(), "https://expo.test/push", null).send(listOf(push))
        }
        assertEquals("Expo refused (429).", refused.message)
        handler = { throw IllegalStateException("connection refused") }
        val down = assertFailsWith<NotificationCallException> {
            ResendEmailSender(http(), "https://resend.test", "k", "x").send(EmailMessage("a", "b", "c"))
        }
        assertEquals("Resend did not answer.", down.message)
        handler = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { ExpoPushSender(http(), "https://e.test", null).send(listOf(push)) }
    }

    @Test
    fun theModuleRegistersItsChannelsAndItsClients() = runTest {
        assertEquals("notifications", NotificationsModule.name)
        assertEquals("create_notifications", NotificationsModule.migrations.single().name)
        val app = koinApplication {
            allowOverride(true)
            modules(
                NotificationsModule.koinModule,
                module {
                    single<ConfigSource> { MapConfigSource(mapOf("OSTROM_RESEND_API_KEY" to "re_1")) }
                    single<PushDevicesRepository> { MemoryPushDevices() }
                },
            )
        }
        assertIs<ExpoPushSender>(app.koin.get<PushSender>())
        assertTrue(app.koin.get<EmailSender>().configured)
        // Without the identity module's addresses, the email channel knows none.
        assertEquals(setOf("push", "email"), app.koin.getAll<GateChannel>().map { it.name }.toSet())
        app.koin.get<HttpClient>(NOTIFICATIONS_HTTP)
        app.close()
    }
}
