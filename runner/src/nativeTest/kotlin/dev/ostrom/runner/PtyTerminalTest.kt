// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A real PTY: the shell runs, reads keystrokes, sees its size and ends. */
class PtyTerminalTest {
    private val environment = mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color")

    private suspend fun Terminal.readUntil(marker: String): String {
        val seen = StringBuilder()
        while (marker !in seen) seen.append(read()?.decodeToString() ?: break)
        return seen.toString()
    }

    @Test
    fun aShellRunsOnThePtyAndEnds() = runBlocking {
        withTimeout(20.seconds) {
            val terminal = PtyTerminal.start("echo ready; read line; echo got-\$line; stty size", environment, "/")
            assertTrue("ready" in terminal.readUntil("ready"))
            terminal.resize(90, 33)
            terminal.write("abc\n".encodeToByteArray())
            val rest = terminal.readUntil("33 90")
            assertTrue("got-abc" in rest, rest)
            while (terminal.read() != null) Unit
            assertNull(terminal.read())
            terminal.close()
            terminal.close()
        }
    }

    @Test
    fun terminateStopsTheWholeProcessGroup() = runBlocking {
        withTimeout(20.seconds) {
            val terminal = PtyTerminal.spawn(
                listOf("/bin/sh", "-c", "trap '' TERM; echo up; sleep 100"),
                environment,
                "/",
                80,
                24,
                300.milliseconds,
            )
            terminal.readUntil("up")
            terminal.terminate()
            while (terminal.read() != null) Unit
            terminal.close()
        }
    }

    @Test
    fun theSessionScriptStartsTheAgentWithItsModelAndFirstMessage() = runBlocking {
        withTimeout(20.seconds) {
            val agent = environment + mapOf(
                "OSTROM_AGENT_COMMAND" to "echo",
                "OSTROM_AGENT_MODEL" to "m-1",
                "OSTROM_AGENT_PROMPT" to "fix it",
                "OSTROM_WORKSPACE" to "/tmp",
                "HOME" to "/tmp/ostrom-runner-test-home",
            )
            val terminal = PtyTerminal.start(SessionScript.FIRST, agent, "/tmp")
            // One read until the last line: a chunk can hold both, and a second wait would block on the shell.
            val output = terminal.readUntil("The agent exited")
            assertTrue("--model m-1 fix it" in output, output)
            terminal.terminate()
            terminal.close()
        }
    }

    @Test
    fun aMissingDirectoryEndsTheChildAtOnce() = runBlocking {
        withTimeout(20.seconds) {
            val terminal = PtyTerminal.start("echo never", environment, "/no/such/directory")
            while (terminal.read() != null) Unit
            terminal.close()
        }
    }
}
