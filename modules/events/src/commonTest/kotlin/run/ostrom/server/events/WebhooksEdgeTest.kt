// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.SendResult
import run.ostrom.server.events.domain.WebhookDeliveryRecord
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.events.infrastructure.HmacWebhookSigner
import run.ostrom.server.events.infrastructure.HttpWebhookSender
import run.ostrom.server.events.infrastructure.WebhooksConfig
import run.ostrom.server.events.presentation.managesWebhooks
import run.ostrom.server.events.presentation.toResponse
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import run.ostrom.server.kernel.network.PinnedDnsTimeout
import run.ostrom.server.kernel.network.isPublicAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The network edge of the webhooks: the signature, the secrets, the sender's checks and its error codes. */
class WebhooksEdgeTest {
    @Test
    fun theSignatureIsTheHexHmacOfTheTimestampAndTheBodyAsTeamCodesReceiversCheck() = runTest {
        val signer = HmacWebhookSigner()
        // python: hmac.new(b"whsec_test", b'1761069900.{"a":1}', hashlib.sha256).hexdigest()
        assertEquals(
            "v1=ad83192d99305a9bc1e63209e73d0e851ae13a843edfe4ba357d6b8e95aa33f0",
            signer.signature("whsec_test", 1_761_069_900, """{"a":1}"""),
        )
        val secrets = List(5) { signer.newSecret() }
        assertTrue(secrets.all { Regex("whsec_[A-Za-z0-9_-]{43}").matches(it) }, secrets.toString())
        assertEquals(5, secrets.toSet().size)
    }

    @Test
    fun theOperatorOptsIntoPrivateTargets() {
        val allowed = MapConfigSource(mapOf("OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS" to "true"))
        assertTrue(WebhooksConfig.from(allowed).allowPrivateTargets)
        assertFalse(WebhooksConfig.from(MapConfigSource(emptyMap())).allowPrivateTargets)
    }

    @Test
    fun aRetryIsWorthItWithoutAnAnswerOnAServerErrorOrWhenAskedToSlowDown() {
        assertTrue(SendResult(null, "connect").retryable)
        assertFalse(SendResult(null, "connect").delivered)
        for (status in listOf(500, 503, 408, 429)) assertTrue(SendResult(status, null).retryable, "$status")
        for (status in listOf(400, 404, 301)) assertFalse(SendResult(status, null).retryable, "$status")
        assertTrue(SendResult(299, null).delivered)
        assertFalse(SendResult(300, null).delivered)
        assertFalse(SendResult(199, null).delivered)
    }

    @Test
    fun onlyAPersonsInteractiveLoginOrACloudAdminKeyManagesWebhooks() {
        fun user(channel: PrincipalChannel, signedIn: Boolean = false) = AuthenticatedPrincipal(
            "u", PrincipalKind.USER, setOf("cloud:write"), channel = channel, signedIn = signedIn,
        )
        assertTrue(user(PrincipalChannel.BROWSER).managesWebhooks())
        assertTrue(user(PrincipalChannel.MOBILE_APP).managesWebhooks())
        assertTrue(user(PrincipalChannel.API_TOKEN, signedIn = true).managesWebhooks(), "ostrom login")
        assertFalse(user(PrincipalChannel.API_TOKEN).managesWebhooks(), "A personal token, whatever its cloud scopes")
        assertTrue(AuthenticatedPrincipal("k", PrincipalKind.SERVICE, setOf("cloud:admin")).managesWebhooks())
        assertFalse(AuthenticatedPrincipal("k", PrincipalKind.SERVICE, setOf("cloud:write")).managesWebhooks())
        assertFalse(AuthenticatedPrincipal("a", PrincipalKind.AGENT, setOf("cloud:admin")).managesWebhooks())
    }

    private fun sender(
        answers: List<String> = listOf("93.184.215.14"),
        lookupFails: Throwable? = null,
        engine: suspend (HttpRequestData) -> Unit = {},
        requests: MutableList<HttpRequestData> = mutableListOf(),
    ) = HttpWebhookSender(
        HttpClient(
            MockEngine { request ->
                requests += request
                engine(request)
                respond("ignored", HttpStatusCode.Accepted)
            },
        ),
        WebhookTargets(allowPrivate = false),
        ::isPublicAddress,
    ) { _ -> lookupFails?.let { throw it } ?: answers }

    @Test
    fun aDeliveryIsPostedWithItsHeadersAndOnlyItsStatusIsKept() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val result = sender(requests = requests)
            .send("https://hooks.example.com/in", mapOf("Ostrom-Event" to "cloud.todo"), """{"a":1}""")
        assertEquals(SendResult(202, null), result)
        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("cloud.todo", request.headers["Ostrom-Event"])
        assertEquals("""{"a":1}""", (request.body as TextContent).text)
    }

    @Test
    fun aRefusedTargetIsNeverRequested() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        suspend fun codeOf(url: String, answers: List<String> = listOf("93.184.215.14"), fails: Throwable? = null) =
            sender(answers, fails, requests = requests).send(url, emptyMap(), "{}").error
        assertEquals("host_not_allowed", codeOf("https://10.0.0.1/"))
        assertEquals("host_not_allowed", codeOf("https://rebind.example.com/", listOf("93.184.215.14", "10.0.0.1")))
        assertEquals("host_not_allowed", codeOf("https://odd.example.com/", listOf("not an address")))
        assertEquals("dns", codeOf("https://none.example.com/", fails = PinnedDnsRefusal()))
        assertEquals("dns", codeOf("https://slow.example.com/", fails = PinnedDnsTimeout()))
        assertTrue(requests.isEmpty())
        assertEquals(null, codeOf("https://hooks.example.com/"))
        assertEquals(1, requests.size)
    }

    @Test
    fun networkFailuresBecomeShortCodesAndACancellationIsNotOne() = runTest {
        val url = "https://hooks.example.com/"
        suspend fun codeOf(failure: Throwable) = sender(engine = { throw failure }).send(url, emptyMap(), "").error
        assertEquals("timeout", codeOf(HttpRequestTimeoutException(url, 10_000)))
        assertEquals("timeout", codeOf(IOException("x", SocketTimeout())))
        assertEquals("tls", codeOf(TlsFailure()))
        assertEquals("connect", codeOf(IOException("refused")))
        assertFailsWith<CancellationException> { codeOf(CancellationException("stop")) }
    }

    @Test
    fun aFinishedDeliveryShowsNoNextAttemptAndNeverItsPayload() {
        val at = Instant.parse("2026-11-13T10:00:00Z")
        val record = WebhookDeliveryRecord(
            Uuid.random(), Uuid.random(), 3, "cloud.todo", "{\"secret\":1}", DeliveryState.DELIVERED, 1, at, 200, null,
            at, at, false,
        )
        val shown = record.toResponse()
        assertNull(shown.nextAttemptAt)
        assertEquals(at.toString(), shown.deliveredAt)
        assertEquals("delivered" to 3L, shown.state to shown.eventSeq)
        assertNull(record.copy(state = DeliveryState.PENDING, deliveredAt = null).toResponse().deliveredAt)
    }

    private class SocketTimeout : Exception()

    private class TlsFailure : Exception()
}
