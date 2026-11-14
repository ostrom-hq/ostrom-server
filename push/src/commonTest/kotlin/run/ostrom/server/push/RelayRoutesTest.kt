// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/RelayTest.kt; see NOTICE.
package run.ostrom.server.push

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelayRegisterRequest
import run.ostrom.core.api.models.PushRelayRegisterResponse
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

class RelayRoutesTest {
    private suspend fun ApplicationTestBuilder.send(instance: Instance, request: PushRelaySendRequest): HttpResponse {
        val bytes = bodyOf(request)
        val headers = instance.headers(bytes, Clock.System.now().epochSeconds)
        return client.post(PushRelay.SEND_PATH) {
            header(PushRelay.INSTANCE_HEADER, headers.instance)
            header(PushRelay.TIMESTAMP_HEADER, headers.timestamp)
            header(PushRelay.SIGNATURE_HEADER, headers.signature)
            contentType(ContentType.Application.Json)
            setBody(bytes)
        }
    }

    @Test
    fun theRelayAnswersItsHealthProbe() = testApplication {
        application { relayModule(Relay(FakeFcm())) }
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }

    @Test
    fun aKeyIsRegisteredAsItsOwnId() = testApplication {
        application { relayModule(Relay(FakeFcm())) }
        val server = Instance.generate()
        val registered = client.post(PushRelay.REGISTER_PATH) {
            contentType(ContentType.Application.Json)
            setBody(OstromJson.encodeToString(PushRelayRegisterRequest(server.id)))
        }
        assertEquals(HttpStatusCode.OK, registered.status)
        val body = OstromJson.decodeFromString<PushRelayRegisterResponse>(registered.bodyAsText())
        assertEquals(PushRelayRegisterResponse(server.id), body)
    }

    @Test
    fun aBadKeyOrABodyThatIsNotARequestIsRefused() = testApplication {
        application { relayModule(Relay(FakeFcm())) }
        for (body in listOf("""{"publicKey":"nope"}""", "{", """{"other":1}""")) {
            val answer = client.post(PushRelay.REGISTER_PATH) { setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, answer.status)
            assertEquals(
                PushRelaySendResponse("rejected", error = "invalid_key"),
                OstromJson.decodeFromString<PushRelaySendResponse>(answer.bodyAsText()),
            )
        }
    }

    @Test
    fun aSignedRequestIsSentAndAnUnsignedOneIsRefused() = testApplication {
        val fcm = FakeFcm()
        application { relayModule(Relay(fcm)) }
        val sent = send(Instance.generate(), sampleRequest)
        assertEquals(HttpStatusCode.OK, sent.status)
        val answer = OstromJson.decodeFromString<PushRelaySendResponse>(sent.bodyAsText())
        assertEquals(PushRelaySendResponse("sent"), answer)
        assertNull(sent.headers[HttpHeaders.RetryAfter])
        assertEquals(listOf(sampleRequest), fcm.sent)

        val unsigned = client.post(PushRelay.SEND_PATH) { setBody(bodyOf()) }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        assertEquals(1, fcm.sent.size)
    }

    @Test
    fun aRefusedRequestForTheQuotaSaysWhenToComeBack() = testApplication {
        application { relayModule(Relay(FakeFcm(), limits = RelayLimits(perInstance = 1))) }
        val server = Instance.generate()
        assertEquals(HttpStatusCode.OK, send(server, sampleRequest).status)
        val limited = send(server, sampleRequest.copy(collapseKey = "abcdef0123456789"))
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
        val body = OstromJson.decodeFromString<PushRelaySendResponse>(limited.bodyAsText())
        assertEquals("rate_limited", body.error)
    }

    @Test
    fun aBodyLargerThanTheLimitIsRefusedWhetherItsLengthIsDeclaredOrNot() = testApplication {
        val fcm = FakeFcm()
        application { relayModule(Relay(fcm)) }
        val declared = client.post(PushRelay.SEND_PATH) { setBody(ByteArray(MAX_BODY_BYTES + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)

        val streamed = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) =
                channel.writeFully(ByteArray(MAX_BODY_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post(PushRelay.SEND_PATH) { setBody(streamed) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post(PushRelay.REGISTER_PATH) { setBody(streamed) }.status)
        assertEquals(emptyList(), fcm.sent)
    }

    @Test
    fun aBodyOfTheLimitIsRead() = testApplication {
        application { relayModule(Relay(FakeFcm())) }
        val streamed = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(ByteArray(MAX_BODY_BYTES))
        }
        // Read whole, then refused as what it is: no signature.
        assertEquals(HttpStatusCode.Unauthorized, client.post(PushRelay.SEND_PATH) { setBody(streamed) }.status)
    }
}
