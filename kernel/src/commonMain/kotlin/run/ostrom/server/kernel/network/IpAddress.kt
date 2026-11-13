// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.network

private const val IPV4_BYTES = 4
private const val IPV6_WORDS = 8
private const val MAX_OCTET = 255
private const val HEX = 16
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private val IPV4_MAPPED_PREFIX = ByteArray(10) + byteArrayOf(-1, -1)
private val OCTET = Regex("0|[1-9][0-9]{0,2}")
private val HEX_WORD = Regex("[0-9a-fA-F]{1,4}")

/**
 * Parses an IP address as it appears in a socket address or an `X-Forwarded-For` hop: IPv4 (`203.0.113.7`) or IPv6
 * (`2001:db8::1`, `[::1]`, `fe80::1%eth0`, `::ffff:192.0.2.1`). An IPv4-mapped IPv6 address gives its IPv4 bytes,
 * so that `::ffff:10.0.0.1` matches `10.0.0.0/8`. Anything else (a host name, a port, a leading zero) is refused.
 *
 * @param text The address.
 *
 * @return Its 4 or 16 bytes, or null when it is not an IP address.
 */
fun parseIpAddress(text: String): ByteArray? {
    val address = text.trim().removeSurrounding("[", "]").substringBefore('%')
    return if (':' in address) parseIpv6(address)?.let(::unmapIpv4) else parseIpv4(address)
}

private fun unmapIpv4(bytes: ByteArray): ByteArray {
    val prefix = IPV4_MAPPED_PREFIX.size
    val mapped = bytes.copyOfRange(0, prefix).contentEquals(IPV4_MAPPED_PREFIX)
    return if (mapped) bytes.copyOfRange(prefix, bytes.size) else bytes
}

private fun parseIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    val valid = parts.size == IPV4_BYTES && parts.all { OCTET.matches(it) && it.toInt() <= MAX_OCTET }
    return if (valid) ByteArray(IPV4_BYTES) { parts[it].toInt().toByte() } else null
}

private fun parseIpv6(text: String): ByteArray? {
    val sides = text.split("::")
    val head = words(sides.first())
    val tail = if (sides.size == 2) words(sides.last()) else emptyList()
    val words = when {
        sides.size > 2 || head == null || tail == null -> null
        sides.size == 1 -> head
        head.size + tail.size < IPV6_WORDS -> head + List(IPV6_WORDS - head.size - tail.size) { 0 } + tail
        else -> null
    }
    return words?.takeIf { it.size == IPV6_WORDS }?.let { w ->
        ByteArray(IPV6_WORDS * 2) { (w[it / 2] shr (if (it % 2 == 0) BYTE_BITS else 0)).toByte() }
    }
}

/** The 16-bit words of one side of `::`; a trailing IPv4 address counts as two words. Null when malformed. */
private fun words(text: String): List<Int>? {
    if (text.isEmpty()) return emptyList()
    val parts = text.split(':')
    val embedded = '.' in parts.last()
    val hex = if (embedded) parts.dropLast(1) else parts
    val ipv4 = if (embedded) parseIpv4(parts.last())?.let { listOf(it.word(0), it.word(2)) } else emptyList()
    return if (ipv4 == null || !hex.all(HEX_WORD::matches)) null else hex.map { it.toInt(HEX) } + ipv4
}

private fun ByteArray.word(at: Int): Int =
    (this[at].toInt() and BYTE_MASK shl BYTE_BITS) or (this[at + 1].toInt() and BYTE_MASK)

/**
 * Writes an address in its canonical form, so that one client is always one key: IPv4 dotted, IPv6 in lower case
 * with the longest run of two or more zero words written `::` (the first on a tie, RFC 5952).
 *
 * @param bytes The 4 or 16 bytes of [parseIpAddress].
 *
 * @return The address.
 */
fun formatIpAddress(bytes: ByteArray): String = if (bytes.size == IPV4_BYTES) {
    bytes.joinToString(".") { (it.toInt() and BYTE_MASK).toString() }
} else {
    formatIpv6(bytes)
}

private fun formatIpv6(bytes: ByteArray): String {
    val words = List(IPV6_WORDS) { bytes.word(it * 2) }
    val runs = words.indices
        .filter { words[it] == 0 && (it == 0 || words[it - 1] != 0) }
        .map { start -> start to (start until IPV6_WORDS).takeWhile { words[it] == 0 }.count() }
    val hex = words.map { it.toString(HEX) }
    val longest = runs.maxByOrNull { it.second }?.takeIf { it.second > 1 }
    return if (longest == null) {
        hex.joinToString(":")
    } else {
        val (start, length) = longest
        hex.subList(0, start).joinToString(":") + "::" + hex.subList(start + length, IPV6_WORDS).joinToString(":")
    }
}
