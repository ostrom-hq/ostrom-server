// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.network

import dev.ostrom.server.kernel.config.InvalidConfigException

private const val BYTE_BITS = 8
private val PREFIX = Regex("[0-9]{1,3}")
private val HEADER_NAME = Regex("[A-Za-z0-9-]+")

/**
 * An IP network, `address/prefix` (`10.0.0.0/8`, `fd00::/8`); a bare address is a network of one.
 *
 * @property bytes The network address.
 * @property prefix How many leading bits a member shares with it.
 */
class IpNetwork private constructor(private val bytes: ByteArray, private val prefix: Int) {
    /**
     * Whether an address belongs to the network. An IPv4 address never belongs to an IPv6 network, nor the reverse
     * (an IPv4-mapped IPv6 address is read as IPv4 by [parseIpAddress]).
     *
     * @param address The address bytes, from [parseIpAddress].
     *
     * @return True when its first [prefix] bits are the network's.
     */
    operator fun contains(address: ByteArray): Boolean =
        address.size == bytes.size && (0 until prefix).all { address.bit(it) == bytes.bit(it) }

    private fun ByteArray.bit(index: Int): Int =
        (this[index / BYTE_BITS].toInt() shr (BYTE_BITS - 1 - index % BYTE_BITS)) and 1

    /** Reading a network. */
    companion object {
        /**
         * Parses `address/prefix` or a bare address.
         *
         * @param text The network.
         *
         * @return The network, or null when [text] is not one.
         */
        fun parse(text: String): IpNetwork? {
            val address = parseIpAddress(text.substringBefore('/')) ?: return null
            val bits = address.size * BYTE_BITS
            val prefix = if ('/' in text) text.substringAfter('/').takeIf(PREFIX::matches)?.toInt() else bits
            return prefix?.takeIf { it <= bits }?.let { IpNetwork(address, it) }
        }
    }
}

/**
 * The reverse proxies whose `X-Forwarded-For` the server believes (`OSTROM_TRUSTED_PROXIES`, CIDR list). With none,
 * the client is always the socket's peer and the header is ignored, so a client cannot choose the address its rate
 * limits and logs are keyed on (S2-02).
 *
 * @property networks The trusted networks.
 * @property clientHeader The header in which a trusted proxy gives the client's address
 *   (`OSTROM_CLIENT_ADDRESS_HEADER`, such as Cloudflare's `CF-Connecting-IP`), read before `X-Forwarded-For`; none by
 *   default. Only for proxies that set it themselves and that clients cannot reach around.
 */
class TrustedProxies(private val networks: List<IpNetwork>, val clientHeader: String? = null) {
    /** Whether no proxy is trusted. */
    val isEmpty: Boolean get() = networks.isEmpty()

    /**
     * The client's address. When the peer is a trusted proxy, the address of [clientHeader] wins when it is one;
     * otherwise the `X-Forwarded-For` hops are read from the right
     * (the hop the proxy itself appended) and the first address that is not a trusted proxy is the client: what a
     * client writes to the left of it is never believed. A malformed hop (a host name, a port) stops the walk at the
     * last address read, a trusted proxy's, so that clients behind a proxy writing ports share its address.
     *
     * @param peer The socket's peer address.
     * @param forwardedFor The `X-Forwarded-For` header values, in order.
     * @param headerValue The value of [clientHeader] in the request, if any.
     *
     * @return The client's address in canonical form ([formatIpAddress]); [peer] as given only when it is not an
     *   IP address.
     */
    fun clientAddress(peer: String, forwardedFor: List<String>, headerValue: String? = null): String {
        val peerBytes = parseIpAddress(peer) ?: return peer
        val client = if (networks.any { peerBytes in it }) {
            headerValue?.let { parseIpAddress(it.trim()) } ?: forwardedClient(forwardedFor)
        } else {
            null
        }
        return formatIpAddress(client ?: peerBytes)
    }

    private fun forwardedClient(forwardedFor: List<String>): ByteArray? {
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val readable = hops.asReversed().map(::parseIpAddress).takeWhile { it != null }.filterNotNull()
        return readable.firstOrNull { address -> networks.none { address in it } } ?: readable.lastOrNull()
    }

    /** Reading the trusted proxies. */
    companion object {
        /** No trusted proxy: `X-Forwarded-For` is never read. */
        val NONE: TrustedProxies = TrustedProxies(emptyList())

        /**
         * Parses `OSTROM_TRUSTED_PROXIES`, a comma-separated list of networks (`10.0.0.0/8, fd00::/8`).
         *
         * @param value The list, or null for none.
         * @param clientHeader `OSTROM_CLIENT_ADDRESS_HEADER`, or null for none.
         *
         * @return The trusted proxies.
         *
         * @throws InvalidConfigException When an entry is not an IP address or network, when the header is not a
         *   header name, or when a header is given without a trusted proxy (it would never be read).
         */
        fun parse(value: String?, clientHeader: String? = null): TrustedProxies {
            val entries = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val header = headerName(clientHeader, entries.isNotEmpty())
            if (entries.isEmpty()) return NONE
            return TrustedProxies(
                entries.map {
                    IpNetwork.parse(it) ?: throw InvalidConfigException(
                        "OSTROM_TRUSTED_PROXIES: '$it' is not an IP address or network (address/prefix).",
                    )
                },
                header,
            )
        }

        private fun headerName(value: String?, withProxies: Boolean): String? {
            val name = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!HEADER_NAME.matches(name)) {
                throw InvalidConfigException("OSTROM_CLIENT_ADDRESS_HEADER: '$name' is not a header name.")
            }
            if (!withProxies) {
                throw InvalidConfigException(
                    "OSTROM_CLIENT_ADDRESS_HEADER needs OSTROM_TRUSTED_PROXIES: the header is only read from a " +
                        "trusted proxy.",
                )
            }
            return name
        }
    }
}
