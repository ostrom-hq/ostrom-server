// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/test/kotlin/me/nathanfallet/teamcode/infrastructure/push/PushSendersTest.kt; see NOTICE.
package run.ostrom.server.notifications

import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.FirebaseServiceAccount
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.infrastructure.FirebasePushSender
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The instance's own Firebase project against a fake Google: no provider is ever called. */
class FirebasePushSenderTest {
    private val ios = EncryptedPush("fcm-token-ios", "ios", "c2VhbGVk", "0123456789abcdef0123456789abcdef", 3600)
    private val android = ios.copy(token = "fcm-token-android", platform = "android")
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val clock = TestClock()

    private class Google(var token: () -> Pair<HttpStatusCode, String>, var send: () -> HttpStatusCode) {
        var sendBody = """{"name":"projects/ostrom-app/messages/1"}"""
        val tokenRequests = mutableListOf<HttpRequestData>()
        val sends = mutableListOf<HttpRequestData>()
    }

    private fun google(
        token: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to ACCESS },
        send: () -> HttpStatusCode = { HttpStatusCode.OK },
    ) = Google(token, send)

    private fun HttpRequestData.text(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun client(google: Google) = HttpClient(
        MockEngine { request ->
            if (request.url.host == "oauth2.googleapis.com") {
                google.tokenRequests += request
                val (status, body) = google.token()
                respond(body, status)
            } else {
                google.sends += request
                respond(google.sendBody, google.send())
            }
        },
    ) { expectSuccess = false }

    private suspend fun account(): Pair<FirebaseServiceAccount, RSA.PKCS1.PublicKey> {
        val pair = CryptographyProvider.Default.get(RSA.PKCS1).keyPairGenerator(2048.bits, SHA256).generateKey()
        val pem = pair.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM.Generic).decodeToString()
        return FirebaseServiceAccount("ostrom-app", "push@ostrom-app.iam.gserviceaccount.com", "kid-1", pem) to
            pair.publicKey
    }

    @Test
    fun anIosPushIsAGenericAlertTheExtensionReplacesSignedWithTheServiceAccount() = runTest {
        val (account, publicKey) = account()
        val google = google()
        val sender = FirebasePushSender(account, client(google), clock)

        assertEquals(PushResult.SENT, sender.send(ios))
        assertEquals(PushResult.SENT, sender.send(android))

        assertEquals(1, google.tokenRequests.size, "The access token is kept")
        val form = (google.tokenRequests.single().body as FormDataContent).formData
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        val (header, claims, signature) = form["assertion"]!!.split('.')
        val head = Json.parseToJsonElement(urlSafe.decode(header).decodeToString()).jsonObject
        assertEquals("RS256", head.getValue("alg").jsonPrimitive.content)
        assertEquals("kid-1", head.getValue("kid").jsonPrimitive.content)
        val claimed = Json.parseToJsonElement(urlSafe.decode(claims).decodeToString()).jsonObject
        assertEquals("push@ostrom-app.iam.gserviceaccount.com", claimed.getValue("iss").jsonPrimitive.content)
        val scope = claimed.getValue("scope").jsonPrimitive.content
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", scope)
        assertEquals("https://oauth2.googleapis.com/token", claimed.getValue("aud").jsonPrimitive.content)
        assertEquals(clock.now.epochSeconds + 3600, claimed.getValue("exp").jsonPrimitive.long)
        val verified = publicKey.signatureVerifier()
            .tryVerifySignature("$header.$claims".encodeToByteArray(), urlSafe.decode(signature))
        assertTrue(verified, "Signed with the service account's key")

        val sent = google.sends.first()
        assertEquals("https://fcm.googleapis.com/v1/projects/ostrom-app/messages:send", sent.url.toString())
        assertEquals("Bearer access-1", sent.headers[HttpHeaders.Authorization])
        val message = Json.parseToJsonElement(sent.text()).jsonObject.getValue("message").jsonObject
        assertEquals("fcm-token-ios", message.getValue("token").jsonPrimitive.content)
        assertEquals(mapOf("p" to "c2VhbGVk", "v" to "1"), message.getValue("data").jsonObject.mapValues {
            it.value.jsonPrimitive.content
        })
        val apns = message.getValue("apns").jsonObject
        val headers = apns.getValue("headers").jsonObject
        assertEquals(ios.collapseKey, headers.getValue("apns-collapse-id").jsonPrimitive.content)
        val expiration = headers.getValue("apns-expiration").jsonPrimitive.content
        assertEquals((clock.now.epochSeconds + 3600).toString(), expiration)
        val aps = apns.getValue("payload").jsonObject.getValue("aps").jsonObject
        assertEquals(1, aps.getValue("mutable-content").jsonPrimitive.int)
        val alert = aps.getValue("alert").jsonObject
        assertEquals(FirebasePushSender.GENERIC_TITLE, alert.getValue("title").jsonPrimitive.content)
        assertEquals(FirebasePushSender.GENERIC_BODY, alert.getValue("body").jsonPrimitive.content)
        assertNull(message["android"])

        val second = Json.parseToJsonElement(google.sends.last().text()).jsonObject.getValue("message").jsonObject
        assertNull(second["apns"], "Android gets a data message")
        val config = second.getValue("android").jsonObject
        val expected = mapOf("collapse_key" to ios.collapseKey, "priority" to "high", "ttl" to "3600s")
        assertEquals(expected, config.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun fcmsAnswersBecomeOutcomesAndARevokedAccessTokenIsAskedForAgain() = runTest {
        val (account, _) = account()
        var status = HttpStatusCode.OK
        val google = google(send = { status })
        val sender = FirebasePushSender(account, client(google), clock)
        status = HttpStatusCode.NotFound
        google.sendBody = """{"error":{"code":404,"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""
        assertEquals(PushResult.UNREGISTERED, sender.send(ios), "The token FCM no longer knows")
        google.sendBody = """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}"""
        assertEquals(PushResult.rejected("fcm_404"), sender.send(ios), "A project not found forgets no phone")
        val outcomes = mapOf(
            HttpStatusCode.RequestTimeout to PushResult.retry("fcm_rate_limited"),
            HttpStatusCode.TooManyRequests to PushResult.retry("fcm_rate_limited"),
            HttpStatusCode.ServiceUnavailable to PushResult.retry("fcm_unavailable"),
            HttpStatusCode.BadRequest to PushResult.rejected("fcm_400"),
            HttpStatusCode.Forbidden to PushResult.rejected("fcm_403"),
        )
        for ((answer, outcome) in outcomes) {
            status = answer
            assertEquals(outcome, sender.send(ios), answer.toString())
        }
        assertEquals(1, google.tokenRequests.size)

        status = HttpStatusCode.Unauthorized
        assertEquals(PushResult.retry("fcm_unauthorized"), sender.send(ios))
        status = HttpStatusCode.OK
        assertEquals(PushResult.SENT, sender.send(ios))
        assertEquals(2, google.tokenRequests.size, "A new access token after a 401")

        clock.now += 2.hours
        assertEquals(PushResult.SENT, sender.send(ios))
        assertEquals(3, google.tokenRequests.size, "A new access token once the last one expired")
    }

    @Test
    fun aRefusedOrUnreadableAccessTokenAndAnUnreachableFcmAreRetries() = runTest {
        val (account, _) = account()
        val answers = listOf(
            HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""",
            HttpStatusCode.OK to "not json",
            HttpStatusCode.OK to "[]",
            HttpStatusCode.OK to """{"expires_in":3600}""",
            HttpStatusCode.OK to """{"access_token":42}""",
        )
        for (answer in answers) {
            val google = google(token = { answer })
            assertEquals(PushResult.retry("fcm_token"), FirebasePushSender(account, client(google), clock).send(ios))
            assertTrue(google.sends.isEmpty(), "Nothing sent without an access token")
        }

        // Without expires_in, an hour; an unreadable one too.
        for (body in listOf("""{"access_token":"a"}""", """{"access_token":"a","expires_in":"soon"}""")) {
            val google = google(token = { HttpStatusCode.OK to body })
            val sender = FirebasePushSender(account, client(google), clock)
            assertEquals(PushResult.SENT, sender.send(ios))
            assertEquals(PushResult.SENT, sender.send(ios))
            assertEquals(1, google.tokenRequests.size)
        }

        val down = HttpClient(MockEngine { error("down") }) { expectSuccess = false }
        assertEquals(PushResult.retry("fcm_token"), FirebasePushSender(account, down, clock).send(ios))
        val fcmDown = HttpClient(
            MockEngine { request ->
                if (request.url.host == "oauth2.googleapis.com") {
                    respond("""{"access_token":"a","expires_in":3600}""", HttpStatusCode.OK)
                } else {
                    error("reset")
                }
            },
        ) { expectSuccess = false }
        assertEquals(PushResult.retry("fcm_unreachable"), FirebasePushSender(account, fcmDown, clock).send(ios))
        val unreadable = FirebaseServiceAccount("ostrom-app", "push@ostrom-app.iam.gserviceaccount.com", "k", "x")
        assertEquals(PushResult.retry("fcm_token"), FirebasePushSender(unreadable, client(google()), clock).send(ios))
    }

    @Test
    fun theMessageNamesNoAccountOrRequest() {
        val body = FirebasePushSender.message(android, clock.now) as JsonObject
        assertFalse(body.toString().contains("approval"), "The provider sees a token and a blob")
    }

    private companion object {
        const val ACCESS = """{"access_token":"access-1","expires_in":3600}"""
    }
}
