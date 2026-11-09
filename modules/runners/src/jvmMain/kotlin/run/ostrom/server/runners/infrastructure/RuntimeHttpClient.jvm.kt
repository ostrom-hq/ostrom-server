// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

// The JVM target only runs the tests: it never talks to a real runtime, so the CA bundle is not loaded here.
internal actual fun runtimeHttpClient(caFile: String?, timeoutMillis: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
}
