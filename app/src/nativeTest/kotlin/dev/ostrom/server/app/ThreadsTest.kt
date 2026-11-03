// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class, NativeRuntimeApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.usage.presentation.writeEventStream
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.newFixedThreadPoolContext
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_LINGER
import platform.posix.close
import platform.posix.connect
import platform.posix.linger
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
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
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
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
     * wait or a call cut by its client leaves no thread behind. Nor may the first round grow them past the server's
     * own pools: Ktor's CIO ran every connection on Dispatchers.IO, whose threads start on demand, up to 2048, and
     * never end (41679249042: 95, 95, 93, 93 threads over four rounds, Dispatchers.IO up to its 80th thread; CHARGE
     * 41690263233: 93 threads before a burst, 193 a minute after it).
     */
    @Test
    fun streamsAndWaitsCutByTheirClientsLeaveNoThreadBehind() = runBlocking {
        if (threadCount() == null) return@runBlocking // No /proc: macOS.
        // The clients that reset their connection block a thread each while they wait: their own, started up front.
        val resetting = newFixedThreadPoolContext(RESET_CLIENTS, "client")
        coroutineScope { repeat(RESET_CLIENTS) { launch(resetting) { usleep(HOLD_MICROS) } } }
        val base = threadCount()!!
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
                        launch { tolerant { exchange(selector, port, "/stream") { it == ": connected" } } }
                        launch(resetting) { tolerant { streamThenReset(port) } }
                        launch { tolerant { exchange(selector, port, "/wait") { false } } }
                        launch { tolerant { exchange(selector, port, "/quick", close = true) { false } } }
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
            resetting.close()
        }
        val kinds = namePools()
        println("ROUNDS $variant: base=$base counts=$counts clientErrors=${clientErrors.load()} kinds=$kinds")
        assertTrue(
            counts.max() - base <= SERVER_THREADS + TOLERANCE,
            "from $base threads before the server, $counts after each round: more than its $SERVER_THREADS. $kinds",
        )
        val growth = counts.last() - counts.first()
        assertTrue(
            growth <= TOLERANCE,
            "threads after each round: $counts (+$growth past the first round); by pool: $kinds",
        )
    }

    private var variant = "plain"

    /** Diagnostic: the rounds with 40 MiB held live under a 48 MiB target, autotune off, the pause on. */
    @Test
    fun underHeapPressureWithThePause() = underPressure(pause = true)

    /** Diagnostic: the same pressure, the pause off. */
    @Test
    fun underHeapPressureWithoutThePause() = underPressure(pause = false)

    private fun underPressure(pause: Boolean) {
        variant = "pressure, pause=$pause"
        val target = GC.targetHeapBytes
        val autotune = GC.autotune
        val paused = GC.pauseOnTargetHeapOverflow
        val ballast = List(40) { ByteArray(1 shl 20) { it.toByte() } }
        try {
            GC.collect()
            GC.autotune = false
            GC.targetHeapBytes = 48L shl 20
            GC.pauseOnTargetHeapOverflow = pause
            streamsAndWaitsCutByTheirClientsLeaveNoThreadBehind()
        } finally {
            GC.pauseOnTargetHeapOverflow = paused
            GC.autotune = autotune
            GC.targetHeapBytes = target
            println("ballast ${ballast.sumOf { it.size }}")
        }
    }

    private val clientErrors = AtomicInt(0)

    /** Diagnostic: a client the server reset counts, and the round goes on. */
    private suspend fun tolerant(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.io.IOException) {
            if (clientErrors.incrementAndFetch() == 1) println("first client error: $e")
        } catch (e: IllegalStateException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (clientErrors.incrementAndFetch() == 1) println("first client error: $e")
        }
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

    /**
     * Opens GET /stream on a plain socket, waits for `: connected`, then resets the connection (SO_LINGER 0, so close
     * sends a RST, as a client that crashes or a proxy that drops it), rather than closing it cleanly.
     */
    private fun streamThenReset(port: Int) = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "no socket" }
        try {
            val address = alloc<sockaddr_in>().apply {
                sin_family = AF_INET.convert()
                sin_port = ((port and BYTE) shl BITS or (port shr BITS and BYTE)).convert()
                sin_addr.s_addr = LOOPBACK_IN_NETWORK_ORDER
            }
            check(connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "no connection" }
            val request = "GET /stream HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()
            request.usePinned { send(fd, it.addressOf(0), request.size.convert(), 0) }
            val buffer = allocArray<ByteVar>(BUFFER)
            val seen = StringBuilder()
            var read = recv(fd, buffer, BUFFER.convert(), 0)
            while (read > 0 && ": connected" !in seen) {
                seen.append(buffer.readBytes(read.toInt()).decodeToString())
                if (": connected" !in seen) read = recv(fd, buffer, BUFFER.convert(), 0)
            }
            val reset = alloc<linger>().apply {
                l_onoff = 1
                l_linger = 0
            }
            setsockopt(fd, SOL_SOCKET, SO_LINGER, reset.ptr, sizeOf<linger>().convert())
        } finally {
            close(fd)
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
        const val RESET_CLIENTS = 16
        const val BYTE = 0xff
        const val BITS = 8
        const val LOOPBACK_IN_NETWORK_ORDER = 0x0100007fu // 127.0.0.1, its bytes reversed on a little-endian CPU.

        // The connections' pool (8 by default), and Dispatchers.Default, whose threads may start with the first call.
        const val SERVER_THREADS = 8 + 4
        const val BUFFER = 4096
        const val NAMING_TURNS = 12
        const val IO_PARALLELISM = 64
        const val DEFAULT_TASKS = 16
        const val HOLD_MICROS = 30_000u
        val HEARTBEAT = 100.milliseconds
        val WAIT = 2.seconds
    }
}
