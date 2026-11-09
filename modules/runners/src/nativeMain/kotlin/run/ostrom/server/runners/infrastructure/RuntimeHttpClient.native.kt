// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout

internal actual fun runtimeHttpClient(caFile: String?, timeoutMillis: Long): HttpClient = HttpClient(Curl) {
    expectSuccess = false
    if (caFile != null) engine { caInfo = caFile }
    install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
}
