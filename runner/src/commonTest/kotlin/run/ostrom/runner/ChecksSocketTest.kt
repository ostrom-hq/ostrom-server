// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksSocketTest {
    private val line = """{"sessionId":"s-1","digest":"d","nodeId":"n","scriptId":"lint","script":"true",""" +
        """"directory":"/workspace","extra":1}"""

    private suspend fun ask(input: String, run: suspend (CheckRequest) -> CheckReply, logs: MutableList<String>) =
        ByteChannel().let { output ->
            ChecksSocket.answer(ByteReadChannel(input), output, run, { logs += it })
            output.flushAndClose()
            Json.decodeFromString(CheckReply.serializer(), output.readUTF8Line()!!)
        }

    @Test
    fun oneRequestLineIsAnsweredByOneReplyLine() = runTest {
        val logs = mutableListOf<String>()
        val seen = mutableListOf<CheckRequest>()
        val ran = CheckReply(true, 0, RunnerChecks.PASSED, "abc", "ok", reported = true)

        assertEquals(ran, ask("$line\n", { seen += it; ran }, logs))
        assertEquals(CheckRequest("s-1", "d", "n", "lint", "true", "/workspace"), seen.single())
        assertEquals(listOf("check lint: passed, reported true"), logs)
        // The last line needs no newline.
        assertEquals(ran, ask(line, { ran }, logs))
    }

    @Test
    fun whatIsNotACheckIsAnsweredAsNotRun() = runTest {
        val logs = mutableListOf<String>()
        val never: suspend (CheckRequest) -> CheckReply = { error("never") }

        assertEquals(CheckReply(started = false, outputTail = "no request."), ask("", never, logs))
        assertFalse(ask("not json\n", never, logs).started)
        val long = ask("x".repeat(ChecksSocket.MAX_LINE + 1), never, logs)
        assertEquals("the request is over ${ChecksSocket.MAX_LINE} bytes.", long.outputTail)
        val broken = ask(line, { error("boom") }, logs)
        assertEquals("the runner could not run the check.", broken.outputTail)
        assertEquals(listOf("check failed: IllegalStateException"), logs)
    }

    @Test
    fun aHookThatLeftIsLogged() = runTest {
        val logs = mutableListOf<String>()
        val gone = ByteChannel()
        gone.flushAndClose()

        ChecksSocket.answer(ByteReadChannel("$line\n"), gone, { CheckReply(true) }, { logs += it })

        assertTrue(logs.last().startsWith("check reply failed"))
    }

    @Test
    fun aCancellationIsNeverAnswered() = runTest {
        assertFailsWith<CancellationException> {
            ChecksSocket.answer(ByteReadChannel("$line\n"), ByteChannel(), { throw CancellationException("stop") }, {})
        }
        // A reply the hook does not read suspends; cancelling it propagates.
        var cancelled = false
        val huge = CheckReply(started = false, outputTail = "x".repeat(4 shl 20))
        val writing = launch {
            try {
                ChecksSocket.answer(ByteReadChannel("$line\n"), ByteChannel(), { huge }, {})
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        testScheduler.advanceUntilIdle()
        writing.cancel()
        writing.join()
        assertTrue(cancelled)
    }
}
