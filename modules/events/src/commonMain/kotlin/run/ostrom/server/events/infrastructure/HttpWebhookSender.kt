// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import kotlinx.coroutines.CancellationException
import run.ostrom.server.events.domain.SendResult
import run.ostrom.server.events.domain.WebhookSender
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import java.net.UnknownHostException

/**
 * POSTs one delivery through the kernel's [outboundHttpClient][run.ostrom.server.kernel.network.outboundHttpClient],
 * whose [PinnedDns][run.ostrom.server.kernel.network.PinnedDns] resolver resolves the host once, checks every address
 * and dials one it checked (no DNS rebinding), and which follows no redirect. The answer's body is never read, only its
 * status.
 *
 * The target is judged twice, with one setting: its URL before anything ([WebhookTargets], also for a webhook created
 * while the operator allowed private targets), then the address the connection dials, in the client's own resolver.
 * Errors come back as short codes: the network stack's messages may echo the URL.
 *
 * @param client The outbound client.
 * @param targets Where a webhook may point.
 */
class HttpWebhookSender(
    private val client: HttpClient,
    private val targets: WebhookTargets,
) : WebhookSender {
    override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult =
        if (targets.allows(url)) post(url, headers, body) else SendResult(null, HOST_NOT_ALLOWED)

    private suspend fun post(url: String, headers: Map<String, String>, body: String): SendResult =
        try {
            val status = client.preparePost(url) {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }.execute { it.status.value }
            SendResult(status, null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: HttpRequestTimeoutException) {
            SendResult(null, TIMEOUT)
        } catch (error: Exception) {
            SendResult(null, codeOf(error))
        }

    private fun codeOf(error: Exception): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
        val chain = causes.joinToString(" ") { "${it::class}" }
        return when {
            causes.any { it is PinnedDnsRefusal } -> HOST_NOT_ALLOWED
            causes.any { it is UnknownHostException } -> DNS
            "Timeout" in chain -> TIMEOUT
            TLS_NAMES.containsMatchIn(chain) -> TLS
            else -> CONNECT
        }
    }

    private companion object {
        const val HOST_NOT_ALLOWED = "host_not_allowed"
        const val DNS = "dns"
        const val TIMEOUT = "timeout"
        const val TLS = "tls"
        const val CONNECT = "connect"
        const val MAX_CAUSES = 8
        private val TLS_NAMES = Regex("SSL|Tls|TLS|Certificate|Handshake")
    }
}
