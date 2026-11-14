// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/RelayTest.kt; see NOTICE.
package run.ostrom.server.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.core.credentials.OAuth2TokenResponse
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelaySendResponse

class FirebaseFcmTest {
    private val request = sampleRequest

    private class FakeCredentials : GoogleCredentials() {
        override suspend fun fetchAccessToken() = OAuth2TokenResponse("access", "Bearer", 3600)

        override suspend fun fetchProjectId() = "ostrom-push"
    }

    /** FCM as the relay builds it, with [handler] standing for Google. */
    private fun firebase(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FirebaseFcm =
        FirebaseFcm(
            FakeCredentials(),
            HttpClient(MockEngine(handler)) {
                expectSuccess = true
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            },
        )

    private fun firebase(
        status: HttpStatusCode,
        seen: MutableList<HttpRequestData> = mutableListOf(),
        headers: Headers = headersOf(),
    ): FirebaseFcm = firebase { request ->
        seen += request
        val body = if (status.isSuccess()) {
            """{"name":"projects/ostrom-push/messages/1"}"""
        } else {
            """{"error":{"code":${status.value}}}"""
        }
        respond(
            body,
            status,
            Headers.build {
                appendAll(headers)
                append(HttpHeaders.ContentType, "application/json")
            },
        )
    }

    @Test
    fun aSentMessageGoesToTheProjectWithTheServiceAccountsToken() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertEquals(PushRelaySendResponse("sent"), firebase(HttpStatusCode.OK, seen).send(request))
        assertEquals("/v1/projects/ostrom-push/messages:send", seen.single().url.encodedPath)
        assertEquals("Bearer access", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun theProvidersRefusalsBecomeRelayStatuses() = runTest {
        assertEquals(PushRelaySendResponse("unregistered"), firebase(HttpStatusCode.NotFound).send(request))
        assertEquals(
            PushRelaySendResponse("retry", error = "fcm_auth"),
            firebase(HttpStatusCode.Unauthorized).send(request),
        )
        assertEquals(
            PushRelaySendResponse("rejected", error = "fcm_400"),
            firebase(HttpStatusCode.BadRequest).send(request),
        )
        assertEquals(
            PushRelaySendResponse("rejected", error = "fcm_600"),
            firebase(HttpStatusCode(600, "Odd")).send(request),
        )
    }

    @Test
    fun aRateLimitOrAnOutageIsARetryWithTheProvidersDelayBounded() = runTest {
        suspend fun retry(status: HttpStatusCode, retryAfter: String?): PushRelaySendResponse {
            val headers = retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf()
            return firebase(status, headers = headers).send(request)
        }

        val limited = "fcm_rate_limited"
        val down = "fcm_unavailable"
        assertEquals(PushRelaySendResponse("retry", 30, limited), retry(HttpStatusCode.TooManyRequests, "30"))
        assertEquals(PushRelaySendResponse("retry", null, limited), retry(HttpStatusCode.RequestTimeout, null))
        assertEquals(PushRelaySendResponse("retry", null, down), retry(HttpStatusCode.ServiceUnavailable, null))
        assertEquals(PushRelaySendResponse("retry", 3_600, down), retry(HttpStatusCode.BadGateway, "999999"))
        assertEquals(PushRelaySendResponse("retry", 1, down), retry(HttpStatusCode.BadGateway, "0"))
        assertEquals(PushRelaySendResponse("retry", null, down), retry(HttpStatusCode.BadGateway, "soon"))
    }

    @Test
    fun aCancellationIsNeverAnswered() = runTest {
        val fcm = firebase { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { fcm.send(request) }
    }

    @Test
    fun anIosMessageCarriesTheGenericAlertAndTheEncryptedBlob() {
        val ios = FirebaseFcm.message(request)
        assertEquals(mapOf("p" to request.payload, "v" to "1"), ios.data)
        assertEquals(request.token, ios.token)
        assertEquals(1, ios.apns!!.payload!!.aps.mutableContent)
        assertEquals(PushRelay.GENERIC_TITLE, ios.apns!!.payload!!.aps.alert!!.title)
        assertEquals(PushRelay.GENERIC_BODY, ios.apns!!.payload!!.aps.alert!!.body)
        assertEquals(request.collapseKey, ios.apns!!.headers!!["apns-collapse-id"])
        assertNotNull(ios.apns!!.headers!!["apns-expiration"])
        assertNull(ios.notification, "no visible notification with the text: the app decrypts it")
    }

    @Test
    fun anAndroidMessageCarriesOnlyTheBlob() {
        val android = FirebaseFcm.message(request.copy(platform = "android"))
        assertNull(android.apns)
        assertNull(android.notification)
        assertEquals("3600s", android.android!!.ttl)
        assertEquals(request.collapseKey, android.android!!.collapseKey)
    }

    @Test
    fun theDefaultClientIsClosedWithTheService() {
        val fcm = FirebaseFcm(FakeCredentials())
        fcm.close()
        FirebaseFcm.defaultClient().close()
    }
}
