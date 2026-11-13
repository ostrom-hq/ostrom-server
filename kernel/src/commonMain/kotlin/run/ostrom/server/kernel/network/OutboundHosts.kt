// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.network

import io.ktor.http.parseUrl

// The empty host is refused with them: a URL without a host names nothing.
private val REFUSED_NAMES = setOf("", "metadata", "metadata.google.internal", "metadata.goog", "instance-data")
private const val MAX_PARTS = 4
private const val OCTET_MAX = 255L
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFFL
private const val IPV4_BYTES = 4
private const val HEX = 16
private const val OCTAL = 8
private const val LAST_BYTE = 3
private val HEX_DIGITS = "0123456789abcdef".toSet()
private val OCTAL_DIGITS = '0'..'7'

/**
 * Whether an operator's URL for a service of its own (the Kubernetes API of the cluster the server runs sessions on)
 * may be used: `https` only, and a host that is neither a link-local or unspecified address nor a cloud metadata name
 * (`metadata.google.internal`, `169.254.169.254`, `fd00:ec2::254`). Loopback and private addresses pass: a local
 * `kind` cluster, a private control plane. Every numeric spelling is decoded before it is judged (`2852039166`,
 * `0xa9fea9fe`, `0251.0376.0251.0376`, `::ffff:169.254.169.254` are the metadata address). A DNS name passes: this is
 * a check of the URL, not of what the name resolves to.
 *
 * @param url The URL.
 *
 * @return True when the URL may be used.
 */
fun isReachableUrl(url: String): Boolean {
    // Null for anything that does not parse or has no host (a port above 65535).
    val parsed = parseUrl(url)?.takeIf { it.protocol.name == "https" }
    val host = parsed?.host.orEmpty().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
    return when {
        parsed == null || host in REFUSED_NAMES -> false
        ':' in host -> parseIpAddress(host)?.let(::isReachableAddress) ?: false
        else -> parseLegacyIpv4(host)?.let(::isReachableAddress) ?: true
    }
}

/**
 * Reads a host as `inet_aton` does: one to four numbers, decimal, `0x` hexadecimal or leading-zero octal, the last one
 * filling the bytes that remain (`127.1`, `2130706433`, `0x7f.0.0.1`, `0177.0.0.1`).
 *
 * @param host The host, in lower case.
 *
 * @return The 4 bytes of the address, or null when the host is not a number in one of those spellings.
 */
internal fun parseLegacyIpv4(host: String): ByteArray? {
    val parts = host.split('.')
    val numbers = parts.map { legacyNumber(it) }
    val lastBytes = IPV4_BYTES + 1 - parts.size
    val valid = parts.size <= MAX_PARTS && numbers.none { it == null } &&
        numbers.dropLast(1).all { it!! <= OCTET_MAX } && numbers.last()!! < 1L shl BYTE_BITS * lastBytes
    val head = numbers.dropLast(1).fold(0L) { address, octet -> (address shl BYTE_BITS) or (octet ?: 0L) }
    val address = (head shl BYTE_BITS * lastBytes) or (numbers.last() ?: 0L)
    val bytes = ByteArray(IPV4_BYTES) { ((address shr BYTE_BITS * (LAST_BYTE - it)) and BYTE_MASK).toByte() }
    return bytes.takeIf { valid }
}

private fun legacyNumber(part: String): Long? = when {
    part.startsWith("0x") -> part.drop(2).takeIf { it.isNotEmpty() && it.all(HEX_DIGITS::contains) }?.toLongOrNull(HEX)
    part.length > 1 && part.startsWith("0") -> part.drop(1).takeIf { it.all(OCTAL_DIGITS::contains) }
        ?.toLongOrNull(OCTAL)
    else -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
}
