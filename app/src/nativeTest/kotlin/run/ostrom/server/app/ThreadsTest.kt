// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.server.app

import run.ostrom.server.usage.presentation.writeEventStream
import io.ktor.http.ContentType
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class ThreadsTest {
    /**
     * CHARGE 41672759077: after a burst of streams and waits, the server went from 99 threads at the peak to 198 once
     * every client had gone, and its memory stayed up. Here a real CIO server takes rounds of streams and waits that
     * their clients cut, and of quick calls: past the first round, the threads must stop growing, so that a stream, a
     * wait or a call cut by its client leaves no thread behind. The first round does grow them: CIO runs every
     * connection on Dispatchers.IO, whose threads start on demand and never end (41679249042: 95, 95, 93, 93 threads
     * over four rounds, Dispatchers.IO up to its 80th thread).
     */
    @Test
    fun streamsAndWaitsCutByTheirClientsLeaveNoThreadBehind() = runBlocking {
        if (threadCount() == null) return@runBlocking // No /proc: macOS.
        val open = AtomicInt(0)
        val server = serverEngine(port = 0, host = LOOPBACK) {
            routing {
                get("/stream") {
                    open.incrementAndFetch()
                    try {
                        call.respondBytesWriter(ContentType.Text.EventStream) {
                            writeEventStream(HEARTBEAT, 1.minutes) { onSubscribed ->
                                onSubscribed()
                                flow<String> { awaitCancellation() }
                            }
                        }
                    } finally {
                        open.decrementAndFetch()
                    }
                }
                get("/wait") {
                    open.incrementAndFetch()
                    try {
                        delay(WAIT)
                        call.respondText("late")
                    } finally {
                        open.decrementAndFetch()
                    }
                }
                get("/quick") { call.respondText("ok") }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val selector = SelectorManager(Dispatchers.Default)
        val counts = mutableListOf<Int>()
        try {
            repeat(ROUNDS) {
                coroutineScope {
                    repeat(PER_KIND) {
                        launch { exchange(selector, port, "/stream") { it == ": connected" } }
                        launch { exchange(selector, port, "/wait") { false } }
                        launch { exchange(selector, port, "/quick", close = true) { false } }
                    }
                }
                // The server learns that a stream's client left at its next heartbeats, a wait at its end.
                withTimeout(30.seconds) { while (open.load() > 0) delay(50.milliseconds) }
                delay(500.milliseconds)
                counts += threadCount()!!
            }
        } finally {
            selector.close()
            server.stop(0, STOP_TIMEOUT_MILLIS)
        }
        val kinds = namePools()
        val growth = counts.last() - counts.first()
        assertTrue(
            growth <= TOLERANCE,
            "threads after each round: $counts (+$growth past the first round); by pool: $kinds",
        )
    }

    /** Sends GET [path], reads until [until] matches a line (or the whole answer with [close]), then hangs up. */
    private suspend fun exchange(
        selector: SelectorManager,
        port: Int,
        path: String,
        close: Boolean = false,
        until: (String) -> Boolean,
    ) {
        val socket = aSocket(selector).tcp().connect(LOOPBACK, port)
        try {
            withTimeout(20.seconds) {
                val header = if (close) "Connection: close\r\n" else ""
                val request = "GET $path HTTP/1.1\r\nHost: t\r\n$header\r\n"
                socket.openWriteChannel(autoFlush = true).writeStringUtf8(request)
                val input = socket.openReadChannel()
                if (close) {
                    input.readRemaining().readString()
                } else if (path == "/wait") {
                    delay(300.milliseconds)
                } else {
                    var line = input.readUTF8Line()
                    while (line != null && !until(line)) line = input.readUTF8Line()
                }
            }
        } finally {
            socket.close()
        }
    }

    /** Names every thread of Dispatchers.IO and Dispatchers.Default, then counts the threads by name. */
    private suspend fun namePools(): Map<String, Int> {
        // Each task holds its thread a moment, so that the next ones land on others: the idle threads take turns.
        repeat(NAMING_TURNS) {
            coroutineScope {
                repeat(IO_PARALLELISM) {
                    launch(Dispatchers.IO) {
                        nameThisThread()
                        usleep(HOLD_MICROS)
                    }
                }
                repeat(DEFAULT_TASKS) {
                    launch(Dispatchers.Default) {
                        nameThisThread()
                        usleep(HOLD_MICROS)
                    }
                }
            }
        }
        val names = threadNames()
        val highestIo = names.mapNotNull { it.removePrefix("io-").toIntOrNull() }.maxOrNull() ?: -1
        return names.groupingBy { it.replace(Regex("-[0-9]+$"), "") }.eachCount() + ("highest io-N" to highestIo)
    }

    private fun threadCount(): Int? = readFile("/proc/self/status")
        ?.lineSequence()?.firstOrNull { it.startsWith("Threads:") }?.substringAfter(":")?.trim()?.toInt()

    private fun threadNames(): List<String> {
        val dir = opendir("/proc/self/task") ?: return emptyList()
        val names = mutableListOf<String>()
        try {
            var entry = readdir(dir)
            while (entry != null) {
                names += entry.pointed.d_name.toKString()
                entry = readdir(dir)
            }
        } finally {
            closedir(dir)
        }
        return names.filterNot { it.startsWith(".") }.mapNotNull { readFile("/proc/self/task/$it/comm")?.trim() }
    }

    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            return memScoped {
                val buffer = allocArray<ByteVar>(BUFFER)
                buildString {
                    while (fgets(buffer, BUFFER, file) != null) append(buffer.toKString())
                }
            }
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val ROUNDS = 4
        const val PER_KIND = 100
        const val TOLERANCE = 8
        const val BUFFER = 4096
        const val NAMING_TURNS = 12
        const val IO_PARALLELISM = 64
        const val DEFAULT_TASKS = 16
        const val HOLD_MICROS = 30_000u
        val HEARTBEAT = 100.milliseconds
        val WAIT = 2.seconds
    }
}
