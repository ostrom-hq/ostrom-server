// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import java.net.InetAddress

/**
 * An HTTP client for a URL someone else supplied (an MCP server, a webhook), protected against server-side request
 * forgery at connection time rather than on the URL's shape, as TeamCode does: the CIO engine's `dnsResolver` is a
 * [PinnedDns], which resolves the host once, has every address checked ([isPublicAddress], or [isReachableAddress]
 * for an operator who opted into private destinations) and hands the engine exactly those addresses to dial. A name
 * that changes its answer between the check and the connection cannot reach a private address, and the network policy
 * of the cluster is no longer the only barrier (compose has none). The TLS session stays between the client and the
 * real host (SNI, certificate and `Host` header of the URL).
 *
 * Redirects are not followed. If a caller turns them on in [configure], each hop resolves its host again, checked
 * like the first, so a redirect to a private or link-local host is refused too.
 *
 * @param allowPrivate True to allow loopback and private addresses (never link-local, metadata or unspecified ones).
 * @param lookup The system lookup, replaced by tests.
 * @param configure Further client settings (timeouts, for instance), applied last.
 *
 * @return The client.
 */
fun outboundHttpClient(
    allowPrivate: Boolean = false,
    lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
    engine { dnsResolver = PinnedDns.resolver(if (allowPrivate) ::isReachableAddress else ::isPublicAddress, lookup) }
    configure()
}
