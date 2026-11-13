// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.network.formatIpAddress
import run.ostrom.server.kernel.network.parseIpAddress

/** The bytes of an IPv6 address [rateLimitSource] keeps: a /64, what one client is given. */
private const val IPV6_CLIENT_BYTES = 8

/** The bytes of an IPv4 address. */
private const val IPV4_ADDRESS_BYTES = 4

/**
 * Who a per-address rate limit counts: an IPv4 client by its address, an IPv6 client by its /64 (one client is given a
 * whole /64 and picks any address in it, so counting addresses would let it pass every limit), as TeamCode counts its
 * sign-ins. An IPv4-mapped IPv6 address is read as IPv4; what is not an address is counted as it is.
 *
 * @param source The client address, as the server resolved it behind its trusted proxies.
 *
 * @return The key part naming the client.
 */
fun rateLimitSource(source: String): String {
    val bytes = parseIpAddress(source) ?: return source
    return if (bytes.size == IPV4_ADDRESS_BYTES) {
        formatIpAddress(bytes)
    } else {
        val network = ByteArray(bytes.size) { if (it < IPV6_CLIENT_BYTES) bytes[it] else 0 }
        formatIpAddress(network) + "/" + IPV6_CLIENT_BYTES * Byte.SIZE_BITS
    }
}
