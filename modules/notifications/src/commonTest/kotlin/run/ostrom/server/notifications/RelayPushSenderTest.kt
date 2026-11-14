// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/test/kotlin/me/nathanfallet/teamcode/infrastructure/push/PushSendersTest.kt; see NOTICE.
package run.ostrom.server.notifications

import run.ostrom.core.api.models.PushRelay
import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.PushRelayIdentity
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.infrastructure.CachedRelaySenders
import run.ostrom.server.notifications.infrastructure.RelayPushSender
import run.ostrom.server.secrets.domain.SecretCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Ostrom Push relay client against a fake relay that checks every signature, as the relay does. */
class RelayPushSenderTest {
    private val url = "https://push.example.com"
    private val push = EncryptedPush("fcm-token", "ios", "c2VhbGVk", "0123456789abcdef0123456789abcdef", 3600)
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val clock = TestClock()

    /** Seals as `<context>|<base64>`, and opens only in the same context. */
    private class Base64Cipher : SecretCipher {
        override suspend fun seal(plaintext: ByteArray, context: String): String =
            "$context|${Base64.encode(plaintext)}"

        override suspend fun open(envelope: String, context: String): ByteArray? =
            envelope.removePrefix("$context|").takeIf { it != envelope }?.let { Base64.decode(it) }
    }

    private class Identities : PushRelayIdentitiesRepository {
        val rows = mutableMapOf<String, PushRelayIdentity>()

        override suspend fun find(relayUrl: String): PushRelayIdentity? = rows[relayUrl]

        override suspend fun create(relayUrl: String, identity: PushRelayIdentity): PushRelayIdentity =
            rows.getOrPut(relayUrl) { identity }

        override suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?) {
            val row = rows[relayUrl]?.takeIf { it.publicKey == publicKey } ?: return
            rows[relayUrl] = row.copy(instanceId = instanceId)
        }
    }

    /** A relay: the id of a key is the key, every send must be signed by it. */
    private class Relay {
        var register: suspend MockRequestHandleScope.(String) -> HttpResponseData = { key ->
            respond("""{"instanceId":"$key"}""", HttpStatusCode.Created)
        }
        var send: suspend MockRequestHandleScope.() -> HttpResponseData = { respond("""{"status":"sent"}""") }
        val registrations = mutableListOf<String>()
        val sends = mutableListOf<HttpRequestData>()
        val unsigned = mutableListOf<String>()
    }

    private fun HttpRequestData.text(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private suspend fun verified(request: HttpRequestData): Boolean {
        val key = urlSafe.decode(request.headers.getValue(PushRelay.INSTANCE_HEADER))
        val body = request.text()
        val digest = CryptographyProvider.Default.get(SHA256).hasher().hash(body.encodeToByteArray()).toHexString()
        val signing = PushRelay.signingString(
            "POST", PushRelay.SEND_PATH, request.headers.getValue(PushRelay.TIMESTAMP_HEADER).toLong(), digest,
        )
        val signature = urlSafe.decode(request.headers.getValue(PushRelay.SIGNATURE_HEADER))
        return CryptographyProvider.Default.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, key)
            .signatureVerifier()
            .tryVerifySignature(signing.encodeToByteArray(), signature)
    }

    private fun client(relay: Relay) = HttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                PushRelay.REGISTER_PATH -> {
                    val registration = Json.parseToJsonElement(request.text()).jsonObject
                    val key = registration.getValue("publicKey").jsonPrimitive.content
                    relay.registrations += key
                    relay.register(this, key)
                }
                else -> {
                    relay.sends += request
                    if (!verified(request)) relay.unsigned += request.url.encodedPath
                    relay.send(this)
                }
            }
        },
    ) { expectSuccess = false }

    private fun sender(
        relay: Relay,
        identities: Identities = Identities(),
        cipher: SecretCipher = Base64Cipher(),
        http: HttpClient = client(relay),
    ) = RelayPushSender(url, identities, cipher, http, clock)

    @Test
    fun theFirstPushRegistersAKeyEveryReplicaSharesThenEverySendIsSigned() = runTest {
        val relay = Relay()
        val identities = Identities()
        val sender = sender(relay, identities)

        assertEquals(PushResult.SENT, sender.send(push))
        assertEquals(PushResult.SENT, sender.send(push))

        val identity = assertNotNull(identities.rows[url])
        assertEquals(listOf(identity.publicKey), relay.registrations, "Registered once")
        assertEquals(identity.publicKey, identity.instanceId)
        assertEquals(32, urlSafe.decode(identity.publicKey).size, "The raw Ed25519 key")
        assertTrue(identity.sealedPrivateKey.startsWith("${RelayPushSender.context(url)}|"), "Sealed for this relay")
        assertFalse(identity.toString().contains(identity.sealedPrivateKey), "The key is never printed")
        assertEquals(2, relay.sends.size)
        assertEquals(emptyList(), relay.unsigned)
        val sent = relay.sends.first()
        assertEquals("$url${PushRelay.SEND_PATH}", sent.url.toString())
        assertEquals(clock.now.epochSeconds.toString(), sent.headers[PushRelay.TIMESTAMP_HEADER])
        val body = Json.parseToJsonElement(sent.text()).jsonObject
        assertEquals(
            mapOf("token" to "fcm-token", "platform" to "ios", "payload" to "c2VhbGVk",
                "collapseKey" to "0123456789abcdef0123456789abcdef"),
            body.filterKeys { it != "ttlSeconds" }.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(3600L, body.getValue("ttlSeconds").jsonPrimitive.long)

        // Another replica reads the same identity: no second key, no second registration.
        assertEquals(PushResult.SENT, sender(relay, identities).send(push))
        assertEquals(1, relay.registrations.size)
        assertEquals(emptyList(), relay.unsigned)
    }

    @Test
    fun theRelaysVerdictBecomesTheOutcome() = runTest {
        val relay = Relay()
        val sender = sender(relay)
        val answers = listOf(
            """{"status":"unregistered"}""" to PushResult.UNREGISTERED,
            """{"status":"retry","retryAfterSeconds":5}""" to PushResult.retry("provider_unavailable"),
            """{"status":"rejected","error":"bad_token"}""" to PushResult.rejected("relay_rejected"),
            "not json" to PushResult.rejected("relay_rejected"),
        )
        for ((answer, outcome) in answers) {
            relay.send = { respond(answer) }
            assertEquals(outcome, sender.send(push), answer)
        }
        val statuses = listOf(
            HttpStatusCode.Forbidden to PushResult.rejected("relay_instance_blocked"),
            HttpStatusCode.RequestTimeout to PushResult.retry("relay_rate_limited"),
            HttpStatusCode.TooManyRequests to PushResult.retry("relay_rate_limited"),
            HttpStatusCode.BadGateway to PushResult.retry("relay_unavailable"),
            HttpStatusCode.BadRequest to PushResult.rejected("relay_refused"),
            HttpStatusCode.Found to PushResult.rejected("relay_refused"),
        )
        for ((status, outcome) in statuses) {
            relay.send = { respond("", status) }
            assertEquals(outcome, sender.send(push), status.toString())
        }
        assertEquals(1, relay.registrations.size)
    }

    @Test
    fun aRelayThatForgotTheKeyGetsItRegisteredAgainAtTheNextPush() = runTest {
        val relay = Relay()
        val identities = Identities()
        val sender = sender(relay, identities)
        relay.send = { respond("", HttpStatusCode.Unauthorized) }

        assertEquals(PushResult.retry("relay_unknown_instance"), sender.send(push))
        assertNull(identities.rows.getValue(url).instanceId, "Forgotten")
        relay.send = { respond("""{"status":"sent"}""") }
        assertEquals(PushResult.SENT, sender.send(push))

        val key = identities.rows.getValue(url).publicKey
        assertEquals(listOf(key, key), relay.registrations, "The same key, registered again")
    }

    @Test
    fun twoPushesTheRelayRefusesTogetherForgetTheKeyOnce() = runTest {
        val relay = Relay()
        val identities = Identities()
        val sender = sender(relay, identities)
        assertEquals(PushResult.SENT, sender.send(push))
        val both = CompletableDeferred<Unit>()
        relay.send = {
            if (relay.sends.size == 3) both.complete(Unit)
            both.await()
            respond("", HttpStatusCode.Unauthorized)
        }

        val outcomes = listOf(async { sender.send(push) }, async { sender.send(push) }).awaitAll()

        assertEquals(List(2) { PushResult.retry("relay_unknown_instance") }, outcomes)
        assertNull(identities.rows.getValue(url).instanceId)
    }

    @Test
    fun aRegistrationThatFailsOrAKeyThatNoLongerOpensIsTriedAgainLater() = runTest {
        val refused = Relay().apply { register = { respond("", HttpStatusCode.ServiceUnavailable) } }
        assertEquals(PushResult.retry("relay_registration"), sender(refused).send(push))
        assertEquals(emptyList(), refused.sends)

        val odd = Relay().apply { register = { respond("""{"instanceId":"not an id!"}""", HttpStatusCode.OK) } }
        assertEquals(PushResult.retry("relay_registration"), sender(odd).send(push))

        val identities = Identities()
        identities.rows[url] = PushRelayIdentity("key", "another-relay|AAAA", "key")
        val relay = Relay()
        assertEquals(PushResult.retry("relay_registration"), sender(relay, identities).send(push))
        assertEquals(emptyList(), relay.sends)
    }

    @Test
    fun aRelayThatDoesNotAnswerIsTriedAgainLater() = runTest {
        val relay = Relay()
        val identities = Identities()
        assertEquals(PushResult.SENT, sender(relay, identities).send(push))
        val down = HttpClient(MockEngine { throw IOException("down") }) { expectSuccess = false }

        assertEquals(PushResult.retry("relay_unreachable"), sender(relay, identities, http = down).send(push))
    }

    @Test
    fun aRelaySenderIsKeptWhileTheUrlStaysTheSame() = runTest {
        val made = mutableListOf<String>()
        val senders = CachedRelaySenders { relayUrl ->
            made += relayUrl
            FcmSender { PushResult.SENT }
        }

        val first = senders.sender(url)
        assertSame(first, senders.sender(url))
        senders.sender("https://other.example.com")
        senders.sender(url)

        assertEquals(listOf(url, "https://other.example.com", url), made)
    }
}
