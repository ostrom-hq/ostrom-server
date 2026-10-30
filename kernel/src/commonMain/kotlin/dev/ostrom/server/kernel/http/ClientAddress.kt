// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

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
            val headerValue = proxies.clientHeader?.let { call.request.headers[it] }
            val client = proxies.clientAddress(peer, forwardedFor, headerValue)
            if (client != peer) {
                call.mutableOriginConnectionPoint.remoteAddress = client
                call.mutableOriginConnectionPoint.remoteHost = client
            }
        }
    }
}
