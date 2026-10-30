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
            return false
        }
        if (parsed.protocol.name != "https") return false
        if (allowPrivate) return true
        val host = parsed.host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (host.isEmpty() || host == "localhost" || INTERNAL_SUFFIXES.any { host == it.drop(1) || host.endsWith(it) }) return false
        ipv4(host)?.let { return isPublicIpv4(it) }
        if (':' in host) return isPublicIpv6(host)
        return '.' in host
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.length in 1..3 } ?: return null }
        return octets.toIntArray()
    }

    private fun isPublicIpv4(o: IntArray): Boolean = !(
        o[0] == 0 || o[0] == 10 || o[0] == 127 || o[0] >= 224 ||
            (o[0] == 100 && o[1] in 64..127) ||
            (o[0] == 169 && o[1] == 254) ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 192 && o[1] == 0 && o[2] in setOf(0, 2)) ||
            (o[0] == 198 && o[1] in 18..19) ||
            (o[0] == 198 && o[1] == 51 && o[2] == 100) ||
            (o[0] == 203 && o[1] == 0 && o[2] == 113)
        )

    private fun isPublicIpv6(host: String): Boolean {
        // Global unicast is 2000::/3; documentation (2001:db8::/32) and every embedded-IPv4 form are refused.
        val first = host.substringBefore(':').toIntOrNull(16) ?: return false
        if (first !in 0x2000..0x3fff) return false
        if (host.startsWith("2001:db8") || host.startsWith("2001:0db8") || host.startsWith("2002:")) return false
        return '.' !in host
    }

    private companion object {
        val INTERNAL_SUFFIXES = listOf(".local", ".internal", ".localhost", ".cluster.local", ".svc", ".home.arpa")
    }
}
