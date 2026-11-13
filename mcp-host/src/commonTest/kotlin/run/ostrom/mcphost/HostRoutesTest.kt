// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import run.ostrom.mcphost.HostHarness.Companion.KEY
import run.ostrom.mcphost.HostHarness.Companion.TOKEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostRoutesTest {
    private class FakeService : HostService {
        var ready = true
        var failure: Exception? = null
        val retained = mutableListOf<Collection<String>>()

        private fun fail() = failure?.let { throw it }

        override suspend fun op(request: OpsRequest): OpsResponse {
            fail()
            return OpsResponse(JsonObject(emptyMap()), null, cold = true, state = ProcessStates.READY)
        }

        override suspend fun warm(request: WarmRequest): KeyState {
            fail()
            return KeyState(request.key, ProcessStates.STARTING)
        }

        override suspend fun retain(keys: Collection<String>): Int {
            fail()
            retained += keys
            return 2
        }

        override suspend fun stop(key: String): Boolean {
            fail()
            return key == KEY
        }

        override suspend fun status(): HostStatus = HostStatus("1.0.0", "1", listOf("npm"), 16, false, emptyList())

        override fun ready(): Boolean = ready
    }

    private val config = HostHarness.CONFIG.copy(maxRequestBytes = 2_048)
    private val spec = HostProtocol.json.encodeToString(ProcessSpec.serializer(), HostHarness.spec())
    private val ops = """{"key":"$KEY","spec":$spec,"op":"list","deadlineMs":1000,"maxResponseBytes":2048}"""

    private fun hostTest(block: suspend ApplicationTestBuilder.(FakeService, MutableList<String>) -> Unit) =
        testApplication {
            val service = FakeService()
            val unexpected = mutableListOf<String>()
            application { mcpHost(config, service) { unexpected += it } }
            block(service, unexpected)
        }

    private suspend fun ApplicationTestBuilder.send(
        path: String,
        body: String,
        token: String? = "Bearer $TOKEN",
        protocol: String? = "1",
    ): HttpResponse = client.post(path) {
        token?.let { header(HttpHeaders.Authorization, it) }
        protocol?.let { header(HostProtocol.HEADER, it) }
        setBody(body)
    }

    @Test
    fun theProbesNeedNoToken() = hostTest { service, _ ->
        assertEquals("ok", client.get("/health").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
        service.ready = false
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
    }

    @Test
    fun everyOperationIsServed() = hostTest { service, _ ->
        val listed = send(HostProtocol.OPS, ops)
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue("\"cold\":true" in listed.bodyAsText())
        val warmed = send(HostProtocol.WARM, """{"key":"$KEY","spec":$spec}""")
        assertEquals(HttpStatusCode.Accepted, warmed.status)
        assertEquals("""{"stopped":2}""", send(HostProtocol.RETAIN, """{"keys":["$KEY"]}""").bodyAsText())
        assertEquals(listOf(KEY), service.retained.single().toList())
        assertEquals("""{"stopped":true}""", send(HostProtocol.STOP, """{"key":"$KEY"}""").bodyAsText())
        val status = client.get(HostProtocol.STATUS) {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            header(HostProtocol.HEADER, "1")
        }
        assertTrue("\"version\":\"1.0.0\"" in status.bodyAsText())
    }

    @Test
    fun aMissingOrWrongTokenIsUnauthorized() = hostTest { _, _ ->
        for (token in listOf(null, "Bearer wrong", "Basic $TOKEN")) {
            val refused = send(HostProtocol.OPS, ops, token = token)
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue("unauthorized" in refused.bodyAsText())
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get(HostProtocol.STATUS).status)
        assertEquals(HttpStatusCode.Unauthorized, send(HostProtocol.WARM, "{}", token = null).status)
    }

    @Test
    fun anotherProtocolVersionIsRefused() = hostTest { _, _ ->
        for (protocol in listOf(null, "2")) {
            val refused = send(HostProtocol.STOP, """{"key":"$KEY"}""", protocol = protocol)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("unsupported_protocol" in refused.bodyAsText())
        }
    }

    @Test
    fun aLargeOrMalformedBodyIsRefused() = hostTest { _, _ ->
        assertEquals(HttpStatusCode.PayloadTooLarge, send(HostProtocol.RETAIN, "x".repeat(4_096)).status)
        val malformed = send(HostProtocol.RETAIN, "{")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue("bad_request" in malformed.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, send(HostProtocol.RETAIN, "{}").status)
    }

    @Test
    fun refusalsAndFailuresHaveTheirStatus() = hostTest { service, unexpected ->
        service.failure = HostFailure(HostErrors.BAD_REQUEST, "bad key")
        assertEquals(HttpStatusCode.BadRequest, send(HostProtocol.STOP, """{"key":"x"}""").status)
        service.failure = HostFailure(HostErrors.SHUTTING_DOWN, "stopping")
        val warm = send(HostProtocol.WARM, """{"key":"$KEY","spec":$spec}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, warm.status)
        service.failure = IllegalStateException("/var/mcp/secret path")
        val failed = send(HostProtocol.OPS, ops)
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertTrue("secret" !in failed.bodyAsText())
        assertEquals(listOf("IllegalStateException"), unexpected)
        service.failure = object : RuntimeException() {}
        assertEquals(HttpStatusCode.InternalServerError, send(HostProtocol.STOP, """{"key":"$KEY"}""").status)
        assertEquals("Exception", unexpected.last())
        service.failure = HostFailure(HostErrors.BAD_REQUEST, "")
        assertEquals(HttpStatusCode.BadRequest, send(HostProtocol.RETAIN, """{"keys":[]}""").status)
    }
}
