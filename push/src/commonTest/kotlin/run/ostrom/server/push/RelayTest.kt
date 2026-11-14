// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/RelayTest.kt; see NOTICE.
package run.ostrom.server.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

class RelayTest {
    private val request = sampleRequest

    private suspend fun Relay.sendSigned(instance: Instance, body: ByteArray, at: Long): RelayAnswer {
        val headers = instance.headers(body, at)
        return send(headers.instance, headers.timestamp, headers.signature, body, parseRequest)
    }

    @Test
    fun aKeyIsItsOwnIdAndNothingElseIsRegistered() = runTest {
        val relay = Relay(FakeFcm())
        val server = Instance.generate()
        assertEquals(server.id, relay.register(server.id))
        assertNull(relay.register("not-a-key"))
        assertNull(relay.register("!!!!"))
        assertNull(relay.register(Instance.urlSafe.encode(ByteArray(31))))
        assertNull(relay.register(""))
    }

    @Test
    fun aSignedRequestIsSentOnceAndACopyIsAnsweredWithoutAnotherPush() = runTest {
        val fcm = FakeFcm()
        val relay = Relay(fcm, now = { 1_700_000_000L })
        val server = Instance.generate()
        val body = bodyOf()
        val headers = server.headers(body, 1_700_000_000L)
        assertEquals(
            RelayAnswer(200, PushRelaySendResponse("sent")),
            relay.send(headers.instance, headers.timestamp, headers.signature, body, parseRequest),
        )
        assertEquals(listOf(request), fcm.sent)
        assertEquals(200, relay.send(headers.instance, headers.timestamp, headers.signature, body, parseRequest).status)
        assertEquals(1, fcm.sent.size)
    }

    @Test
    fun theProvidersOwnAnswerIsPassedOn() = runTest {
        val fcm = FakeFcm(PushRelaySendResponse("unregistered"))
        val relay = Relay(fcm, now = { 50 })
        assertEquals(
            RelayAnswer(200, PushRelaySendResponse("unregistered")),
            relay.sendSigned(Instance.generate(), bodyOf(), 50),
        )
    }

    @Test
    fun aRequestFromNoKnownInstanceIsRefused() = runTest {
        val relay = Relay(FakeFcm(), now = { 100 })
        val server = Instance.generate()
        val body = bodyOf()
        val headers = server.headers(body, 100)
        suspend fun unknown(instance: String?) =
            relay.send(instance, headers.timestamp, headers.signature, body, parseRequest)

        for (instance in listOf(null, "", "unknown", "x".repeat(65), Instance.urlSafe.encode(ByteArray(31)))) {
            val expected = RelayAnswer(401, PushRelaySendResponse("rejected", error = "unknown_instance"))
            assertEquals(expected, unknown(instance))
        }
    }

    @Test
    fun aRequestWithoutAValidTimeOrSignatureIsRefused() = runTest {
        val fcm = FakeFcm()
        val relay = Relay(fcm, now = { 1_000 })
        val server = Instance.generate()
        val body = bodyOf()
        val headers = server.headers(body, 1_000)
        val other = Instance.generate()
        suspend fun refusal(timestamp: String?, signature: String?, instance: String = headers.instance): String? {
            val answer = relay.send(instance, timestamp, signature, body, parseRequest)
            assertEquals(401, answer.status)
            assertEquals("rejected", answer.body.status)
            return answer.body.error
        }

        assertEquals("bad_signature", refusal(null, headers.signature))
        assertEquals("bad_signature", refusal("yesterday", headers.signature))
        assertEquals("bad_signature", refusal(headers.timestamp, null))
        assertEquals("bad_signature", refusal(headers.timestamp, "!!!"))
        assertEquals("bad_signature", refusal(headers.timestamp, Instance.urlSafe.encode(ByteArray(32))))
        assertEquals("bad_signature", refusal(headers.timestamp, Instance.urlSafe.encode(ByteArray(64))))
        // The signature of another instance, the signature of another time, another body under the signature.
        assertEquals("bad_signature", refusal(headers.timestamp, other.headers(body, 1_000).signature))
        assertEquals("bad_signature", refusal("1001", headers.signature))
        val changed = bodyOf(request.copy(token = "u".repeat(40)))
        assertEquals(
            "bad_signature",
            relay.send(headers.instance, headers.timestamp, headers.signature, changed, parseRequest).body.error,
        )
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun aStaleOrFutureRequestIsRefused() = runTest {
        val relay = Relay(FakeFcm(), now = { 10_000 })
        val server = Instance.generate()
        val body = bodyOf()
        for (at in listOf(10_000 - PushRelay.MAX_SKEW_SECONDS - 1, 10_000 + PushRelay.MAX_SKEW_SECONDS + 1)) {
            val answer = relay.sendSigned(server, body, at)
            assertEquals(RelayAnswer(401, PushRelaySendResponse("rejected", error = "stale_request")), answer)
        }
        assertEquals(200, relay.sendSigned(server, body, 10_000 - PushRelay.MAX_SKEW_SECONDS).status)
    }

    @Test
    fun aBlockedInstanceIsRefusedAfterItsSignatureIsChecked() = runTest {
        val blocked = Instance.generate()
        val fcm = FakeFcm()
        val relay = Relay(fcm, blocked = setOf(blocked.id), now = { 100 })
        assertEquals(
            RelayAnswer(403, PushRelaySendResponse("rejected", error = "instance_blocked")),
            relay.sendSigned(blocked, bodyOf(), 100),
        )
        val body = bodyOf()
        val forged = blocked.headers(body, 100).copy(signature = Instance.urlSafe.encode(ByteArray(64)))
        assertEquals(401, relay.send(forged.instance, forged.timestamp, forged.signature, body, parseRequest).status)
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun anInvalidRequestIsRefusedWithItsReason() = runTest {
        val fcm = FakeFcm()
        val relay = Relay(fcm, now = { 100 })
        val server = Instance.generate()

        suspend fun refusal(request: PushRelaySendRequest): String? {
            val answer = relay.sendSigned(server, bodyOf(request), 100)
            assertEquals(400, answer.status)
            assertEquals("rejected", answer.body.status)
            return answer.body.error
        }

        assertEquals("invalid_token", refusal(request.copy(token = "short")))
        assertEquals("invalid_token", refusal(request.copy(token = "t".repeat(PushRelay.MAX_TOKEN_CHARS + 1))))
        assertEquals("invalid_platform", refusal(request.copy(platform = "web")))
        assertEquals("invalid_payload", refusal(request.copy(payload = "x".repeat(PushRelay.MAX_PAYLOAD_CHARS + 1))))
        assertEquals("invalid_payload", refusal(request.copy(payload = "short")))
        assertEquals("invalid_collapse_key", refusal(request.copy(collapseKey = "wait:w1")))
        assertEquals("invalid_ttl", refusal(request.copy(ttlSeconds = Relay.MAX_TTL_SECONDS + 1)))
        assertEquals("invalid_ttl", refusal(request.copy(ttlSeconds = -1)))
        assertEquals("invalid_request", relay.sendSigned(server, "{".encodeToByteArray(), 100).body.error)
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun theValidationAcceptsBothPlatformsAndTheTtlLimits() {
        assertNull(Relay.validate(request))
        assertNull(Relay.validate(request.copy(platform = "android", ttlSeconds = 0)))
        assertNull(Relay.validate(request.copy(ttlSeconds = Relay.MAX_TTL_SECONDS)))
    }

    @Test
    fun quotasHoldPerInstancePerTokenAndInTotal() = runTest {
        var clock = 6_000L
        val relay = Relay(FakeFcm(), limits = RelayLimits(perInstance = 3, perToken = 2, total = 100), now = { clock })
        val server = Instance.generate()

        suspend fun send(token: String): RelayAnswer {
            // A new timestamp each time, so that the replay guard does not answer for them.
            clock += 1
            return relay.sendSigned(server, bodyOf(request.copy(token = token)), clock)
        }
        assertEquals(200, send("a".repeat(30)).status)
        assertEquals(200, send("a".repeat(30)).status)
        val token = send("a".repeat(30))
        assertEquals(429, token.status)
        assertEquals("retry", token.body.status)
        assertEquals("rate_limited", token.body.error)
        assertNotNull(token.retryAfterSeconds)
        assertEquals(token.retryAfterSeconds, token.body.retryAfterSeconds)
        assertEquals(200, send("b".repeat(30)).status, "a refusal counted for nothing")
        assertEquals(429, send("c".repeat(30)).status, "the instance's quota")
        clock += SECONDS_PER_MINUTE
        assertEquals(200, send("c".repeat(30)).status, "a new minute")
    }

    @Test
    fun theWholeRelayHasAQuota() = runTest {
        val relay = Relay(FakeFcm(), limits = RelayLimits(perInstance = 10, perToken = 10, total = 1), now = { 600 })
        assertEquals(200, relay.sendSigned(Instance.generate(), bodyOf(), 600).status)
        assertEquals(429, relay.sendSigned(Instance.generate(), bodyOf(), 600).status)
    }

    @Test
    fun aProviderFailureIsARetry() = runTest {
        val relay = Relay({ error("down") }, now = { 0 })
        assertEquals(
            PushRelaySendResponse("retry", error = "fcm_unreachable"),
            relay.sendSigned(Instance.generate(), bodyOf(), 0).body,
        )
    }

    @Test
    fun aCancellationIsNotAProviderFailure() = runTest {
        val relay = Relay({ throw CancellationException("stopped") }, now = { 0 })
        assertFailsWith<CancellationException> { relay.sendSigned(Instance.generate(), bodyOf(), 0) }
    }

    @Test
    fun theDefaultClockIsTheSystemsAndTheDefaultLimitsAreTheDocumentedOnes() = runTest {
        val relay = Relay(FakeFcm())
        val server = Instance.generate()
        val now = kotlin.time.Clock.System.now().epochSeconds
        assertEquals(200, relay.sendSigned(server, bodyOf(), now).status)
        assertEquals(RelayLimits(600, 20, 6_000), RelayLimits())
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
