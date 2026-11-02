// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.UpstreamRequest
import dev.ostrom.server.gateway.infrastructure.KtorInferenceUpstream
import dev.ostrom.server.gateway.infrastructure.UpstreamStalledException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class KtorInferenceUpstreamTest {
    private val request = UpstreamRequest("https://provider.test/v1/messages", listOf("x-api-key" to "k"), "{}")

    private fun upstream(provider: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorInferenceUpstream(HttpClient(MockEngine { provider(this, it) }), idle = 200.milliseconds)

    /** Reads the whole body as text. */
    private suspend fun KtorInferenceUpstream.read(): String = exchange(request) { answer ->
        val buffer = ByteArray(64)
        buildString {
            var count = answer.body.read(buffer)
            while (count >= 0) {
                append(buffer.decodeToString(0, count))
                count = answer.body.read(buffer)
            }
        }
    }

    @Test
    fun aProviderThatAnswersIsReadToTheEnd() = runTest {
        val body = withContext(Dispatchers.Default) {
            upstream { respond("data: done\n\n", HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream")) }
                .read()
        }
        assertEquals("data: done\n\n", body)
    }

    @Test
    fun aProviderSilentBeforeItsHeadersIsCut() = runTest {
        withContext(Dispatchers.Default) {
            assertFailsWith<UpstreamStalledException> { upstream { awaitCancellation() }.read() }
        }
    }

    @Test
    fun aProviderThatStallsInTheMiddleOfItsStreamIsCut() = runTest {
        val seen = StringBuilder()
        withContext(Dispatchers.Default) {
            val stalled = upstream {
                val stream = ByteChannel(autoFlush = true)
                stream.writeStringUtf8("data: first\n\n")
                respond(stream, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
            }
            assertFailsWith<UpstreamStalledException> {
                stalled.exchange(request) { answer ->
                    val buffer = ByteArray(64)
                    while (true) {
                        val count = answer.body.read(buffer)
                        seen.append(buffer.decodeToString(0, count))
                    }
                }
            }
        }
        assertEquals("data: first\n\n", seen.toString(), "What came before the stall reached the agent")
    }
}
