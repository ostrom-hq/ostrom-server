// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.mutableOriginConnectionPoint

private const val FORWARDED_FOR = "X-Forwarded-For"

/**
 * Sets the call's origin address (`call.request.origin.remoteAddress`, what rate limits and audit read) to the real
 * client behind the trusted proxies of [ClientAddressConfig.proxies] (S2-02). Requests from any other peer keep their
 * socket address, whatever their `X-Forwarded-For` says. Installed first by [configureHttp].
 */
val ClientAddress = createApplicationPlugin("ClientAddress", ::ClientAddressConfig) {
    val proxies = pluginConfig.proxies
    if (!proxies.isEmpty) {
        onCall { call ->
            val peer = call.request.local.remoteAddress
            val client = proxies.clientAddress(peer, call.request.headers.getAll(FORWARDED_FOR).orEmpty())
            if (client != peer) {
                call.mutableOriginConnectionPoint.remoteAddress = client
                call.mutableOriginConnectionPoint.remoteHost = client
            }
        }
    }
}
