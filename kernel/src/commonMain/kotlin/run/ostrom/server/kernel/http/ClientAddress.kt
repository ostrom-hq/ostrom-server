// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.mutableOriginConnectionPoint

private const val FORWARDED_FOR = "X-Forwarded-For"

/**
 * Sets the call's origin address (`call.request.origin.remoteAddress`, what rate limits and audit read) to the real
 * client behind the trusted proxies of [ClientAddressConfig.proxies] (S2-02): their client address header if one is
 * configured, then `X-Forwarded-For`. Requests from any other peer keep their socket address, whatever their headers
 * say. Installed first by [configureHttp].
 */
val ClientAddress = createApplicationPlugin("ClientAddress", ::ClientAddressConfig) {
    val proxies = pluginConfig.proxies
    if (!proxies.isEmpty) {
        onCall { call ->
            val peer = call.request.local.remoteAddress
            val forwardedFor = call.request.headers.getAll(FORWARDED_FOR).orEmpty()
            // Two values: one may be the client's own, so neither is believed.
            val headerValue = proxies.clientHeader?.let { call.request.headers.getAll(it)?.singleOrNull() }
            val client = proxies.clientAddress(peer, forwardedFor, headerValue)
            if (client != peer) {
                call.mutableOriginConnectionPoint.remoteAddress = client
                call.mutableOriginConnectionPoint.remoteHost = client
            }
        }
    }
}
