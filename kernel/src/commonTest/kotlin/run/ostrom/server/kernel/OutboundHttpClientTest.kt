// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import run.ostrom.server.kernel.network.outboundHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/** The client of a URL someone else supplied (TC-CODE-SEC-1, TC-GWY-17). */
class OutboundHttpClientTest {
    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(30.seconds) { block() } }
    }

    private fun mock(handler: MockRequestHandler) =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit) = MockEngine.create {
                addHandler(handler)
                block()
            }
        }

    @Test
    fun aRedirectIsNotFollowedSoItsTargetIsNeverTried() = realTime {
        var requests = 0
        val client = outboundHttpClient(engine = mock {
            requests++
            val location = headersOf(HttpHeaders.Location, "https://169.254.169.254/latest/meta-data/")
            respond("", HttpStatusCode.Found, location)
        })
        try {
            val response = client.get("https://public.test/mcp")
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("https://169.254.169.254/latest/meta-data/", response.headers[HttpHeaders.Location])
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun everyConnectionGoesThroughTheLocalCheck() = realTime {
        val client = outboundHttpClient(engine = mock { respond("") })
        try {
            // Spelled differently per platform (java.net.Proxy on the JVM); the address is in it either way.
            assertNotNull(PROXY.find(client.engine.config.proxy.toString()))
        } finally {
            client.close()
        }
    }

    @Test
    fun aPublicNameThatResolvesToTheMetadataAddressFailsTheRequestAfterOneLookup() = realTime {
        var lookups = 0
        val client = outboundHttpClient(lookup = { lookups++; listOf("169.254.169.254") }, engine = CIO)
        try {
            assertFailsWith<Exception> { client.get("https://metadata.test/latest/meta-data/") }
            assertEquals(1, lookups)
        } finally {
            client.close()
        }
    }

    @Test
    fun anOperatorWhoAllowsPrivateTargetsStillCannotReachMetadata() = realTime {
        var lookups = 0
        val client = outboundHttpClient(
            allowPrivate = true,
            lookup = { lookups++; listOf("169.254.169.254") },
            engine = CIO,
        )
        try {
            assertFailsWith<Exception> { client.get("https://metadata.test/") }
            assertEquals(1, lookups)
        } finally {
            client.close()
        }
    }

    @Test
    fun theDefaultEngineOfThePlatformIsConfiguredLikeTheOthers() = realTime {
        val client = outboundHttpClient()
        try {
            assertNotNull(PROXY.find(client.engine.config.proxy.toString()))
        } finally {
            client.close()
        }
    }

    private companion object {
        val PROXY = Regex("127\\.0\\.0\\.1:(\\d+)")
    }

    @Test
    fun theCallerCanAddItsOwnSettingsLast() = realTime {
        var configured = false
        outboundHttpClient(engine = mock { respond("") }, configure = { configured = true }).close()
        assertEquals(true, configured)
    }
}
