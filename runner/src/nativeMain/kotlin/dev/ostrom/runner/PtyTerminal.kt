// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.runner

import kotlinx.cinterop.ExperimentalForeignApi
import dev.ostrom.runner.pty.ostrom_pty_resize
import dev.ostrom.runner.pty.ostrom_pty_spawn
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.WNOHANG
import platform.posix.errno
import platform.posix.kill
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.strerror
import platform.posix.waitpid
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A [Terminal] on a real PTY: `/bin/sh -c script` started by `forkpty` in C (see `pty.def`).
 *
 * @property pid The shell's pid, leader of its own session and process group.
 * @property master The PTY's master side.
 * @param grace How long [terminate] waits after SIGTERM before SIGKILL.
 */
class PtyTerminal private constructor(val pid: Int, private val master: Int, private val grace: Duration) : Terminal {
    private val closed = AtomicInt(0)
    private val buffer = ByteArray(CHUNK)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        // A short poll before the read: a blocked read() would ignore a cancellation until the PTY speaks.
        while (!readable()) ensureActive()
        var count = readOnce()
        while (count < 0L && errno == EINTR) count = readOnce()
        if (count > 0L) {
            buffer.copyOf(count.toInt())
        } else {
            // 0 or EIO: the last process holding the PTY's slave side is gone.
            reap()
            null
        }
    }

    /** Whether the master has bytes, or has hung up (the read then ends at once); false after [POLL] of quiet. */
    private fun readable(): Boolean = memScoped {
        val request = alloc<pollfd>()
        request.fd = master
        request.events = POLLIN.convert()
        poll(request.ptr, 1.convert(), POLL.inWholeMilliseconds.toInt()) > 0
    }

    private fun readOnce(): Long =
        buffer.usePinned { platform.posix.read(master, it.addressOf(0), CHUNK.convert()).toLong() }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < bytes.size) {
            val written = bytes.usePinned {
                platform.posix.write(master, it.addressOf(offset), (bytes.size - offset).convert())
            }
            if (written.toLong() < 0L) {
                if (errno == EINTR) continue
                return@withContext
            }
            offset += written.toInt()
        }
    }

    override fun resize(columns: Int, rows: Int) {
        ostrom_pty_resize(master, columns, rows)
    }

    override suspend fun terminate() {
        kill(-pid, SIGTERM)
        val deadline = grace.inWholeMilliseconds / POLL.inWholeMilliseconds
        repeat(deadline.toInt()) {
            if (reap()) return
            delay(POLL)
        }
        kill(-pid, SIGKILL)
        reap()
    }

    override fun close() {
        if (closed.compareAndSet(0, 1)) platform.posix.close(master)
    }

    /** Collects the shell's exit if it ended; true once it has. */
    private fun reap(): Boolean = memScoped {
        val status = alloc<IntVar>()
        val result = waitpid(pid, status.ptr, WNOHANG)
        result == pid || result < 0
    }

    /** Constants and readers of the enclosing type. */
    companion object : TerminalStarter {
        private const val CHUNK = 16 * 1024
        private val POLL = 100.milliseconds
        const val SHELL = "/bin/sh"
        private const val COLUMNS = 120
        private const val ROWS = 40
        private val GRACE = 10.seconds

        override fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
            spawn(listOf(SHELL, "-c", script), environment, directory, COLUMNS, ROWS, GRACE)

        /**
         * Starts a program on a new PTY.
         *
         * @param argv The program (absolute path) and its arguments.
         * @param environment Its environment.
         * @param directory Its working directory.
         * @param columns The PTY's columns.
         * @param rows The PTY's rows.
         * @param grace The SIGTERM grace of [terminate].
         *
         * @return The terminal.
         *
         * @throws IllegalStateException When the PTY cannot be opened.
         */
        @Suppress("LongParameterList") // forkpty's own inputs, passed through.
        fun spawn(
            argv: List<String>,
            environment: Map<String, String>,
            directory: String,
            columns: Int,
            rows: Int,
            grace: Duration,
        ): PtyTerminal = memScoped {
            val arguments = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { index, value -> arguments[index] = value.cstr.ptr }
            arguments[argv.size] = null
            val variables = environment.entries.map { "${it.key}=${it.value}" }
            val envp = allocArray<CPointerVar<ByteVar>>(variables.size + 1)
            variables.forEachIndexed { index, value -> envp[index] = value.cstr.ptr }
            envp[variables.size] = null
            val master = alloc<IntVar>()
            val pid = ostrom_pty_spawn(argv.first(), arguments, envp, directory, columns, rows, master.ptr)
            check(pid > 0) { "Could not open a PTY: ${strerror(errno)?.toKString()}" }
            PtyTerminal(pid, master.value, grace)
        }
    }
}
