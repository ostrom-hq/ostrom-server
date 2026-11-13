// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyBuilder
import io.ktor.http.Url
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import kotlinx.coroutines.job

/**
 * An HTTP client for a URL someone else supplied (an MCP server, a webhook), protected against server-side request
 * forgery at connection time rather than on the URL's shape: every connection goes through a [PinnedConnectProxy] of
 * the process, which resolves the host once, has every address checked ([isPublicAddress], or [isReachableAddress]
 * for an operator who opted into private destinations) and dials one of the addresses it checked. A name that
 * changes its answer between the check and the connection cannot reach a private address, and the network policy of
 * the cluster is no longer the only barrier (compose has none). The TLS session stays between the client and the
 * real host (SNI, certificate and `Host` header of the URL).
 *
 * Redirects are not followed. If a caller turns them on in [configure], each hop opens a tunnel of its own, checked
 * like the first, so a redirect to a private or link-local host is refused too.
 *
 * Do not set `NO_PROXY` in the environment of a server that uses it: libcurl honours it before the proxy it was given,
 * so a host it names would be reached without the check.
 *
 * @param allowPrivate True to allow loopback and private addresses (never link-local, metadata or unspecified ones).
 * @param lookup The system lookup, replaced by tests.
 * @param engine The client engine, the platform's default when null (Curl on Linux, Darwin on macOS).
 * @param connector Opens the connection to a checked address, replaced by tests.
 * @param configure Further client settings (timeouts, for instance), applied last.
 *
 * @return The client; closing it stops the proxy.
 */
fun outboundHttpClient(
    allowPrivate: Boolean = false,
    lookup: (String) -> List<String> = ::systemLookup,
    engine: HttpClientEngineFactory<*>? = null,
    connector: suspend (SelectorManager, String, Int) -> Socket = TCP_DIAL,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient {
    val pinned = blockingStart {
        PinnedConnectProxy.start(if (allowPrivate) ::isReachableAddress else ::isPublicAddress, lookup, connector)
    }
    val settings: HttpClientConfig<*>.() -> Unit = {
        followRedirects = false
        expectSuccess = false
        engine { this.proxy = ProxyBuilder.http(Url("http://127.0.0.1:${pinned.port}")) }
        configure()
    }
    val client = if (engine == null) HttpClient(settings) else clientOf(engine, settings)
    client.coroutineContext.job.invokeOnCompletion { pinned.close() }
    return client
}

private fun <T : HttpClientEngineConfig> clientOf(
    engine: HttpClientEngineFactory<T>,
    settings: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(engine) { settings() }

/** Runs the start of the proxy (a bind) where a synchronous factory, such as a Koin definition, needs its port. */
internal expect fun <T> blockingStart(block: suspend () -> T): T
