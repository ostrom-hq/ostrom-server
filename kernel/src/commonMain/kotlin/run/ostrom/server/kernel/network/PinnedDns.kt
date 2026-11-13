// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The host resolved to nothing, or to an address the policy refuses. Its message never echoes the host: the URL is
 * someone else's input.
 */
class PinnedDnsRefusal : IOException("The destination address is not allowed.")

/** The lookup took longer than its deadline: an I/O failure, never a cancellation of the caller. */
class PinnedDnsTimeout : IOException("The destination did not resolve in time.")

private val LOOKUP_DEADLINE = 5.seconds
private const val LOOKUP_THREADS = 16

/**
 * The address check and the connection on one lookup, for [PinnedConnectProxy] (the engines of the server on
 * Kotlin/Native have no resolver hook). The proxy dials exactly the addresses returned here and the client keeps
 * the URL's host name for SNI, certificate verification and the `Host` header.
 * A name that answers a public address to a first lookup and a private one to the next (DNS rebinding) cannot pass
 * the check with one and be dialled with the other: there is no second lookup.
 *
 * Every answer must pass [allowed], not only the first: a name that also resolves to a refused address is refused
 * whole. An IP literal is checked without a lookup; every other numeric spelling (`2130706433`, `0x7f.1`) goes to
 * the system resolver, which reads it as an address, and its answer is checked like any other.
 *
 * Each resolver runs its lookups on its own bound of 16 threads, outside the caller's scope and abandoned after the
 * deadline: a name server that never answers can fill that client's bound, never `Dispatchers.IO` (database, files)
 * nor another client's DNS.
 *
 * @param allowed Whether the address (4 or 16 bytes) may be dialled.
 * @param lookup The system lookup: the addresses of a name, as text.
 * @param deadline How long a lookup may take.
 *
 * @return The function to install as `dnsResolver`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun pinnedResolver(
    allowed: (ByteArray) -> Boolean,
    lookup: (String) -> List<String> = ::systemLookup,
    deadline: Duration = LOOKUP_DEADLINE,
): suspend (String) -> List<String> {
    val lookups = CoroutineScope(blockingDispatcher.limitedParallelism(LOOKUP_THREADS) + SupervisorJob())
    return { name ->
        val host = name.removePrefix("[").removeSuffix("]")
        val answers = if (parseIpAddress(host) != null) {
            listOf(host)
        } else {
            val pending = lookups.async { lookup(host) }
            try {
                withTimeoutOrNull(deadline) { pending.await() } ?: throw PinnedDnsTimeout()
            } finally {
                // Cancelling drops a lookup still waiting for a thread (and does nothing to one that is over); one
                // already running keeps its thread until the system resolver gives up.
                pending.cancel()
            }
        }
        val parsed = answers.map { parseIpAddress(it) }
        if (parsed.isEmpty() || parsed.any { it == null || !allowed(it) }) throw PinnedDnsRefusal()
        answers
    }
}

/**
 * The addresses of a name from the operating system's resolver (`getaddrinfo`): IPv4 and IPv6, as text. Blocking:
 * [pinnedResolver] runs it off the caller's thread.
 *
 * @param host The name.
 *
 * @return The addresses, in the resolver's order; empty when the name has none.
 */
internal expect fun systemLookup(host: String): List<String>

/** The dispatcher of blocking calls (`Dispatchers.IO`, which is not in common code). */
internal expect val blockingDispatcher: CoroutineDispatcher
