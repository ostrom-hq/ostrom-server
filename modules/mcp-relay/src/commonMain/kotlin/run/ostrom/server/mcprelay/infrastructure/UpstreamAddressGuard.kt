// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.infrastructure

import io.ktor.http.parseUrl

/**
 * Refuses upstream URLs that name the server's own network: loopback, private, link-local (cloud metadata),
 * carrier-grade NAT, unspecified and multicast literals, and the internal host names of a cluster. This is the first
 * line, on the URL's shape, for a clear refusal before any call. It is not the barrier: a public name that resolves
 * to a private address, or that answers differently the second time (DNS rebinding), is stopped where the connection
 * is made, by the pinned resolution of the relay's client (`outboundHttpClient`: one lookup, every answer checked,
 * the checked address dialled).
 *
 * @param allowPrivate True for a self-hosted instance whose MCP servers live on its private network
 *   (`OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS=true`); link-local and metadata addresses stay refused at the connection.
 */
class UpstreamAddressGuard(private val allowPrivate: Boolean = false) {
    /**
     * Whether the relay may call this URL.
     *
     * @param url The upstream URL.
     */
    fun allows(url: String): Boolean {
        // Null for anything that does not parse or has no host (a port that is not a number or above 65535).
        val parsed = parseUrl(url)
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
            // 127.1, 0x7f.0.0.1, 0177.0.0.1, 2130706433: the resolver reads them as addresses, so only the
            // canonical dotted-decimal form is checked and every other numeric form is refused.
            isNumericHost(host) -> false
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
}

/** An IPv4 block, such as `10.0.0.0/8`. */
private class Block(cidr: String) {
    private val bits = cidr.substringAfter('/').toInt()
    private val base = cidr.substringBefore('/').split('.')
        .fold(0u) { address, octet -> (address shl OCTET_BITS) or octet.toUInt() }

    fun contains(address: UInt): Boolean = (address xor base) shr (IPV4_BITS - bits) == 0u
}

// Top level rather than in a private companion, whose synthetic accessors no test reaches.
private const val HEX = 16
private const val IPV4_BITS = 32
private const val OCTETS = 4
private const val OCTET_BITS = 8
private val OCTET = 0..255
private const val HEX_DIGITS = "0123456789abcdef"
private val GLOBAL_UNICAST = 0x2000..0x3fff
private val INTERNAL_SUFFIXES = listOf(".local", ".internal", ".localhost", ".cluster.local", ".svc", ".home.arpa")
private val BLOCKED_IPV6_PREFIXES = listOf("2001:db8", "2001:0db8", "2002:")

/** This network, private, loopback, CGNAT, link-local (cloud metadata), documentation, benchmark, multicast. */
private val BLOCKED_IPV4 = listOf(
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
    "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
    "224.0.0.0/3",
).map(::Block)

/** A canonical dotted-decimal IPv4 literal (four octets, no leading zero) as a number, or null. */
private fun ipv4(host: String): UInt? {
    val parts = host.split('.')
    val octets = parts.mapNotNull { part ->
        part.toIntOrNull()?.takeIf { it in OCTET && part == it.toString() }
    }
    return if (parts.size == OCTETS && octets.size == OCTETS) {
        octets.fold(0u) { address, octet -> (address shl OCTET_BITS) or octet.toUInt() }
    } else {
        null
    }
}

/** Whether inet_aton could read the host as an address: one to four decimal, octal or `0x` hex numbers. */
private fun isNumericHost(host: String): Boolean {
    val parts = host.split('.')
    return parts.size <= OCTETS &&
        parts.all { part ->
            if (part.startsWith("0x")) {
                part.drop(2).all { it in HEX_DIGITS }
            } else {
                part.all { it in '0'..'9' }
            }
        }
}
