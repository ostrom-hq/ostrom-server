// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import run.ostrom.server.events.domain.SendResult
import run.ostrom.server.events.domain.WebhookSender
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import run.ostrom.server.kernel.network.PinnedDnsTimeout
import run.ostrom.server.kernel.network.parseIpAddress

/**
 * POSTs one delivery through the kernel's [outboundHttpClient][run.ostrom.server.kernel.network.outboundHttpClient],
 * whose proxy resolves the host once, checks every address and dials one it checked (no DNS rebinding), and which
 * follows no redirect. The answer's body is never read, only its status.
 *
 * The target is judged twice before that: its URL ([WebhookTargets], also for a webhook created while the operator
 * allowed private targets), then the addresses its name has now ([lookup], with [allowed]), only to say why a
 * delivery cannot go (`dns`, `host_not_allowed`) instead of the client's opaque failure; the connection is still
 * checked on its own. Errors come back as short codes: the network stack's messages may echo the URL.
 *
 * @param client The outbound client.
 * @param targets Where a webhook may point.
 * @param allowed Whether an address may be dialled, the policy the client's proxy holds.
 * @param lookup The addresses of a name, as the client's proxy resolves it; refused when it has none.
 */
class HttpWebhookSender(
    private val client: HttpClient,
    private val targets: WebhookTargets,
    private val allowed: (ByteArray) -> Boolean,
    private val lookup: suspend (String) -> List<String>,
) : WebhookSender {
    override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult {
        if (!targets.allows(url)) return SendResult(null, HOST_NOT_ALLOWED)
        refusal(Url(url).host)?.let { return SendResult(null, it) }
        return try {
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
    }

    private suspend fun refusal(host: String): String? = try {
        val answers = lookup(host).map { parseIpAddress(it) }
        if (answers.any { it == null || !allowed(it) }) HOST_NOT_ALLOWED else null
    } catch (_: PinnedDnsRefusal) {
        DNS
    } catch (_: PinnedDnsTimeout) {
        DNS
    }

    private fun codeOf(error: Exception): String {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES)
            .joinToString(" ") { "${it::class}" }
        return when {
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
        val TLS_NAMES = Regex("SSL|Tls|TLS|Certificate|Handshake")
    }
}
