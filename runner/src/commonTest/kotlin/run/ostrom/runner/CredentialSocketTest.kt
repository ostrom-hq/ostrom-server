// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CredentialSocketTest {
    private val request = """{"protocol":"https","host":"github.com","path":"acme/api.git","extra":1}"""
    private val token = Credential("x-access-token", "ghu_one")

    private suspend fun ask(
        input: String,
        logs: MutableList<String> = mutableListOf(),
        source: suspend (CredentialRequest) -> Credential?,
    ): String? = ByteChannel().let { output ->
        CredentialSocket.answer(ByteReadChannel(input), output, source, { logs += it })
        output.flushAndClose()
        output.readUTF8Line()
    }

    @Test
    fun oneRequestLineIsAnsweredByOneCredentialLine() = runTest {
        val seen = mutableListOf<CredentialRequest>()
        assertEquals(
            """{"username":"x-access-token","password":"ghu_one"}""",
            ask("$request\n") { seen += it; token },
        )
        assertEquals(CredentialRequest("https", "github.com", "acme/api.git"), seen.single())
        assertEquals("{}", ask(request) { null }, "No credential")
    }

    @Test
    fun whatIsNotARequestOrASourceThatFailsOrHangsGetsNothing() = runTest {
        val logs = mutableListOf<String>()
        val never: suspend (CredentialRequest) -> Credential? = { error("never") }
        assertEquals("{}", ask("", logs, never))
        assertEquals("{}", ask("not json\n", logs, never))
        assertEquals("{}", ask("x".repeat(CredentialSocket.MAX_LINE + 1), logs, never))
        assertEquals(emptyList(), logs)
        assertEquals("{}", ask(request, logs) { error("server down") })
        assertEquals(listOf("credential source failed: IllegalStateException"), logs)

        val slow = ByteChannel()
        CredentialSocket.answer(ByteReadChannel(request), slow, { awaitCancellation() }, {}, 1.seconds)
        slow.flushAndClose()
        assertEquals("{}", slow.readUTF8Line())

        val gone = ByteChannel()
        gone.flushAndClose()
        CredentialSocket.answer(ByteReadChannel(request), gone, { token }, { logs += it })
        assertTrue(logs.last().startsWith("credential reply failed"))
    }

    @Test
    fun aCancellationIsNeverAnswered() = runTest {
        assertFailsWith<CancellationException> {
            CredentialSocket.answer(ByteReadChannel(request), ByteChannel(), { throw CancellationException("x") }, {})
        }
        // A reply the helper does not read suspends; cancelling it propagates.
        var cancelled = false
        val huge = Credential("x-access-token", "x".repeat(4 shl 20))
        val writing = launch {
            try {
                CredentialSocket.answer(ByteReadChannel(request), ByteChannel(), { huge }, {})
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

    private class Connection(reply: String) : AgentConnection {
        override val input: ByteReadChannel = ByteReadChannel(reply)
        override val output: ByteChannel = ByteChannel()
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun theHelperReadsGitsInputAndPrintsOnlyACredential() {
        val input = sequenceOf("protocol=https", "host=github.com", "path=acme/api.git", "bad", "=x", "", "after=1")
        assertEquals(CredentialRequest("https", "github.com", "acme/api.git"), CredentialHelper.parse(input))
        assertEquals(CredentialRequest(host = "github.com"), CredentialHelper.parse(sequenceOf("host=github.com")))
        assertEquals("username=x-access-token\npassword=ghu_one\n", CredentialHelper.format(token))
        assertEquals("", CredentialHelper.format(null))
    }

    @Test
    fun theHelperAsksTheRunnerAndGetsNothingWhenItCannot() = runTest {
        val connection = Connection("""{"username":"x-access-token","password":"ghu_one"}""" + "\n")
        val github = CredentialRequest("https", "github.com")
        assertEquals(token, CredentialHelper.ask({ connection }, github))
        connection.output.flushAndClose()
        assertEquals("""{"protocol":"https","host":"github.com"}""", connection.output.readUTF8Line())
        assertTrue(connection.closed)

        assertNull(CredentialHelper.ask({ Connection("{}\n") }, github), "No credential")
        assertNull(CredentialHelper.ask({ Connection("") }, github), "The runner left")
        assertNull(CredentialHelper.ask({ error("no socket") }, github))
        assertNull(CredentialHelper.ask({ awaitCancellation() }, github, 1.seconds), "The runner hangs")
        assertFailsWith<CancellationException> { CredentialHelper.ask({ throw CancellationException("x") }, github) }
    }
}
