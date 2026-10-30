// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlin.time.Duration

/**
 * Writes a server-sent event stream: a `: connected` comment once the subscription is active, each item as
 * `data: <item>`, and a `: heartbeat` comment every [heartbeat] so that proxies keep the connection open. Returns when
 * the items end or the client goes away.
 *
 * @param heartbeat The interval of the heartbeat comments.
 * @param subscribe Opens the items; calls its argument once subscribed, before the first item.
 */
suspend fun ByteWriteChannel.writeEventStream(
    heartbeat: Duration,
    subscribe: suspend (onSubscribed: suspend () -> Unit) -> Flow<String>,
) {
    val lock = Mutex()
    suspend fun send(text: String) = lock.withLock {
        writeStringUtf8(text)
        flush()
    }
    try {
        coroutineScope {
            val items = subscribe { send(": connected\n\n") }
            val pings = launch {
                while (true) {
                    delay(heartbeat)
                    send(": heartbeat\n\n")
                }
            }
            try {
                items.collect { send("data: $it\n\n") }
            } finally {
                pings.cancel()
            }
        }
    } catch (_: IOException) {
        // The client closed the stream: there is nobody left to answer, the response simply ends.
    }
}
