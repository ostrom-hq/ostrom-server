// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.network

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16
private const val IPV4_OFFSET = 12
private const val BYTE_BITS = 8
private const val HEX_PAIR = 2
private const val HEX = 16

/** The leading bits of an IPv6 address, written as hexadecimal digits (`fe80` and 10 bits is `fe80::/10`). */
private class Ipv6Prefix(hex: String, private val bits: Int) {
    private val bytes = hex.padEnd(IPV6_BYTES * HEX_PAIR, '0').chunked(HEX_PAIR).map { it.toInt(HEX).toByte() }

    operator fun contains(address: ByteArray): Boolean = (0 until bits).all { index ->
        val shift = BYTE_BITS - 1 - index % BYTE_BITS
        (address[index / BYTE_BITS].toInt() shr shift) and 1 == (bytes[index / BYTE_BITS].toInt() shr shift) and 1
    }
}

// The constants below are well-formed CIDR blocks: parse() cannot return null for them.
private fun ipv4Networks(vararg cidr: String): List<IpNetwork> = cidr.mapNotNull(IpNetwork::parse)

/** This network, private, loopback, CGNAT, link-local (metadata), documentation, benchmark, multicast, reserved. */
private val NON_PUBLIC_IPV4 = ipv4Networks(
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24",
    "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
    "224.0.0.0/3",
)

/** What even an operator who allows private targets is refused: this network and link-local (cloud metadata). */
private val NEVER_REACHABLE_IPV4 = ipv4Networks("0.0.0.0/8", "169.254.0.0/16")

/** Global unicast, the only IPv6 block that is public. */
private val GLOBAL_UNICAST = Ipv6Prefix("2", 3)

/**
 * Inside global unicast, and not public: 6to4 (`2002::/16`, embeds an IPv4 destination), the IETF protocol assignments
 * (`2001::/23`: Teredo, benchmarking, ORCHID, AMT...), documentation (`2001:db8::/32`, `3fff::/20`).
 */
private val NON_PUBLIC_IPV6 = listOf(
    Ipv6Prefix("2002", 16), Ipv6Prefix("2001", 23), Ipv6Prefix("20010db8", 32), Ipv6Prefix("3fff", 20),
)

/** Link-local `fe80::/10` and the AWS metadata block `fd00:ec2::/32`. */
private val NEVER_REACHABLE_IPV6 = listOf(Ipv6Prefix("fe80", 10), Ipv6Prefix("fd000ec2", 32))

/** Mapped (`::ffff:a.b.c.d`), translated (`::ffff:0:a.b.c.d`) and compatible (`::a.b.c.d`) forms: an IPv4 address. */
private val EMBEDDED_IPV4 = listOf(
    Ipv6Prefix("", 96), Ipv6Prefix("00000000000000000000ffff", 96), Ipv6Prefix("0000000000000000ffff0000", 96),
)

/** The loopback `::1`, which the IPv4-compatible form would read as 0.0.0.1. */
private val IPV6_LOOPBACK = Ipv6Prefix("00000000000000000000000000000001", IPV6_BYTES * BYTE_BITS)

/**
 * The addresses a call to a URL someone else supplied may connect to (an MCP server, a webhook): public ones only.
 * Not unspecified, loopback, link-local (cloud metadata included), private (RFC 1918, `fec0::/10`), carrier-grade
 * NAT, multicast, benchmarking, documentation or reserved. For IPv6, global unicast (`2000::/3`) only, without the
 * IETF protocol assignments (`2001::/23`: Teredo, benchmarking, ORCHID) nor documentation (`2001:db8::/32`,
 * `3fff::/20`), and no form that embeds an IPv4 destination (mapped, translated, compatible, 6to4, NAT64) unless that
 * destination is public itself. No lookup happens here.
 *
 * @param address The 4 or 16 bytes of the address.
 *
 * @return True when the address is on the public internet.
 */
fun isPublicAddress(address: ByteArray): Boolean = when (address.size) {
    IPV4_BYTES -> NON_PUBLIC_IPV4.none { address in it }
    IPV6_BYTES -> embeddedIpv4(address)?.let(::isPublicAddress)
        ?: (address in GLOBAL_UNICAST && NON_PUBLIC_IPV6.none { address in it })
    else -> false
}

/**
 * The addresses a call may connect to when the operator opted into private destinations (a self-hosted instance
 * whose MCP servers live on its own network): everything except the unspecified address and link-local ones, so that
 * the cloud metadata endpoints (`169.254.169.254`, `fd00:ec2::/32`) stay out of reach. Loopback and private ranges
 * are allowed. IPv4 forms embedded in IPv6 are judged as the IPv4 address.
 *
 * @param address The 4 or 16 bytes of the address.
 *
 * @return True when the address may be dialled.
 */
fun isReachableAddress(address: ByteArray): Boolean = when {
    address.size == IPV4_BYTES -> NEVER_REACHABLE_IPV4.none { address in it }
    address.size != IPV6_BYTES -> false
    address in IPV6_LOOPBACK -> true
    else -> embeddedIpv4(address)?.let(::isReachableAddress) ?: NEVER_REACHABLE_IPV6.none { address in it }
}

/** The 4 bytes of the IPv4 address an IPv6 one stands for, or null for a native IPv6 address. */
private fun embeddedIpv4(address: ByteArray): ByteArray? =
    if (EMBEDDED_IPV4.any { address in it }) address.copyOfRange(IPV4_OFFSET, IPV6_BYTES) else null
