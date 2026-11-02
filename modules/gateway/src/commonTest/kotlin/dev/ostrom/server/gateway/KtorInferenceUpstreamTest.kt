// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.UpstreamRequest
import dev.ostrom.server.gateway.domain.UpstreamStalledException
import dev.ostrom.server.gateway.infrastructure.KtorInferenceUpstream
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KtorInferenceUpstreamTest {
    private val request = UpstreamRequest("https://provider.test/v1/messages", listOf("x-api-key" to "k"), "{}")

    private fun upstream(
        idle: Duration = 200.milliseconds,
        provider: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = KtorInferenceUpstream(HttpClient(MockEngine { provider(this, it) }), idle)

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
    fun aStreamLongerThanTheSilenceIsReadToTheEndAndTheCallEndsWithIt() = runTest {
        val start = TimeSource.Monotonic.markNow()
        val body = withContext(Dispatchers.Default) {
            upstream(idle = 1.seconds) {
                val stream = ByteChannel(autoFlush = true)
                // Six chunks 300 ms apart: 1.8 s in all, longer than the 1 s silence, never 1 s without one.
                CoroutineScope(Dispatchers.Default).launch {
                    repeat(6) {
                        delay(300.milliseconds)
                        stream.writeStringUtf8("data: $it\n\n")
                    }
                    stream.flushAndClose()
                }
                respond(stream, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
            }.read()
        }
        assertEquals((0..5).joinToString("") { "data: $it\n\n" }, body)
        assertTrue(start.elapsedNow() < 5.seconds, "The guard of the headers does not hold the call after it")
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
