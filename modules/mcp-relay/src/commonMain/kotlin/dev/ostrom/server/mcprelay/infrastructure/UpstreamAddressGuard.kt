// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.infrastructure

import io.ktor.http.Url

/**
 * Refuses upstream URLs that name the server's own network: loopback, private, link-local (cloud metadata),
 * carrier-grade NAT, unspecified and multicast literals, and the internal host names of a cluster. The check is on
 * the URL; a public name that resolves to a private address is stopped by the network policy of the namespace
 * (documented in docs/MODULES.md), not here.
 *
 * @param allowPrivate True for a self-hosted instance whose MCP servers live on its private network
 *   (`OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS=true`).
 */
class UpstreamAddressGuard(private val allowPrivate: Boolean = false) {
    /**
     * Whether the relay may call this URL.
     *
     * @param url The upstream URL.
     */
    fun allows(url: String): Boolean {
        val parsed = try {
            Url(url)
        } catch (_: IllegalArgumentException) {
            null
        }
        return when {
            parsed == null || parsed.protocol.name != "https" -> false
            allowPrivate -> true
            else -> isPublicHost(parsed.host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.'))
        }
    }

    private fun isPublicHost(host: String): Boolean {
        val address = ipv4(host)
        return when {
            host.isEmpty() ||
                host == "localhost" ||
                INTERNAL_SUFFIXES.any {
                    host == it.drop(1) || host.endsWith(it)
                } -> false
            address != null -> BLOCKED_IPV4.none { it.contains(address) }
            ':' in host -> isPublicIpv6(host)
            else -> '.' in host
        }
    }

    private fun isPublicIpv6(host: String): Boolean {
        // Global unicast is 2000::/3; documentation (2001:db8::/32) and every embedded-IPv4 form are refused.
        val first = host.substringBefore(':').toIntOrNull(HEX)
        val global = first != null && first in GLOBAL_UNICAST && BLOCKED_IPV6_PREFIXES.none { host.startsWith(it) }
        return global && '.' !in host
    }

    /** An IPv4 block, such as `10.0.0.0/8`. */
    private class Block(cidr: String) {
        private val bits = cidr.substringAfter('/').toInt()
        private val base = cidr.substringBefore('/').split('.')
            .fold(0u) { address, octet -> (address shl OCTET_BITS) or octet.toUInt() }

        fun contains(address: UInt): Boolean = (address xor base) shr (IPV4_BITS - bits) == 0u
    }

    private companion object {
        const val HEX = 16
        const val IPV4_BITS = 32
        const val OCTETS = 4
        const val OCTET_BITS = 8
        val OCTET = 0..255
        val OCTET_DIGITS = 1..3
        val GLOBAL_UNICAST = 0x2000..0x3fff
        val INTERNAL_SUFFIXES = listOf(".local", ".internal", ".localhost", ".cluster.local", ".svc", ".home.arpa")
        val BLOCKED_IPV6_PREFIXES = listOf("2001:db8", "2001:0db8", "2002:")

        /** This network, private, loopback, CGNAT, link-local (cloud metadata), documentation, benchmark, multicast. */
        val BLOCKED_IPV4 = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
            "224.0.0.0/3",
        ).map(::Block)

        /** A dotted IPv4 literal as a number, or null. */
        fun ipv4(host: String): UInt? {
            val parts = host.split('.')
            val octets = parts.mapNotNull { part ->
                part.toIntOrNull()?.takeIf {
                    part.length in OCTET_DIGITS &&
                        it in OCTET
                }
            }
            return if (parts.size == OCTETS && octets.size == OCTETS) {
                octets.fold(0u) { address, octet -> (address shl OCTET_BITS) or octet.toUInt() }
            } else {
                null
            }
        }
    }
}
