// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The runner and the agent host over a real Unix socket, as in a session pod. */
class AgentSocketTest {
    /** Echoes keystrokes as output; a stop ends it. */
    private class EchoTerminal : Terminal {
        private val output = Channel<ByteArray?>(Channel.UNLIMITED)
        override val exitCode: Int? = 0

        override suspend fun read(): ByteArray? = output.receive()

        override suspend fun write(bytes: ByteArray) {
            output.send(bytes)
        }

        override suspend fun resize(columns: Int, rows: Int) = Unit

        override suspend fun terminate() {
            output.trySend(null)
        }

        override fun close() = Unit
    }

    private class HangingStarter(private val started: CompletableDeferred<Unit>) : TerminalStarter {
        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    @Test
    fun theRunnerDrivesATerminalTheHostRuns() = runBlocking {
        withTimeout(20.seconds) {
            val path = Files.createTempDirectory("ostrom-agent").resolve("agent.sock").toString()
            // A socket file left by a previous host is replaced.
            Files.writeString(java.nio.file.Path.of(path), "stale")
            val selector = SelectorManager(Dispatchers.IO)
            val logs = mutableListOf<String>()
            val ready = CompletableDeferred<String>()
            val starter = object : TerminalStarter {
                var calls = 0

                override suspend fun start(script: String, environment: Map<String, String>, directory: String) =
                    if (calls++ == 0) EchoTerminal() else error("no terminal")
            }
            val host = launch(Dispatchers.IO) {
                AgentSocket.serve(selector, path, AgentHost(starter), { synchronized(logs) { logs += it } }) {
                    ready.complete(it)
                }
            }
            assertEquals(path, ready.await())
            val terminals = RemoteTerminalStarter({ AgentSocket.connect(selector, path) }, retryDelay = 50.milliseconds)
            val terminal = terminals.start("run", mapOf("TERM" to "xterm"), "/workspace")
            terminal.write("hello".encodeToByteArray())
            assertEquals("hello", terminal.read()?.decodeToString())
            val end = async { terminal.read() }
            terminal.terminate()
            assertNull(end.await())
            terminal.close()

            // A terminal the host cannot start ends that connection only, and is logged.
            val failed = terminals.start("run", emptyMap(), "/")
            assertNull(failed.read())
            failed.close()
            host.cancel()
            host.join()
            selector.close()
            assertTrue("agent host listening" in logs)
            assertTrue("terminal connection failed: IllegalStateException" in logs)
        }
    }

    @Test
    fun aHookAsksTheRunnerForACheckOverItsSocket() = runBlocking {
        withTimeout(20.seconds) {
            val path = Files.createTempDirectory("ostrom-checks").resolve("checks.sock").toString()
            val selector = SelectorManager(Dispatchers.IO)
            val ready = CompletableDeferred<Unit>()
            val logs = mutableListOf<String>()
            val ran = CheckReply(true, 0, RunnerChecks.PASSED, "abc", "ok", reported = false)
            val checks = launch(Dispatchers.IO) {
                ChecksSocket.serve(selector, path, { ran }, { synchronized(logs) { logs += it } }) {
                    ready.complete(Unit)
                }
            }
            ready.await()
            val connection = AgentSocket.connect(selector, path)
            val request = """{"sessionId":"s","digest":"d","nodeId":"n","scriptId":"lint","script":"true",""" +
                """"directory":"/workspace"}"""
            connection.output.writeStringUtf8("$request\n")
            connection.output.flush()
            val reply = connection.input.readUTF8Line()
            assertEquals(
                """{"started":true,"exit":0,"outcome":"passed","scriptSha":"abc","outputTail":"ok",""" +
                    """"reported":false}""",
                reply,
            )
            connection.close()
            checks.cancel()
            checks.join()
            selector.close()
            assertTrue("checks listening" in logs)
        }
    }

    @Test
    fun gitsHelperAsksTheRunnerForACredentialOverItsSocket() = runBlocking {
        withTimeout(20.seconds) {
            val path = Files.createTempDirectory("ostrom-credentials").resolve("credential.sock").toString()
            val selector = SelectorManager(Dispatchers.IO)
            val ready = CompletableDeferred<Unit>()
            val logs = mutableListOf<String>()
            val token = Credential("x-access-token", "ghu_one")
            val credentials = launch(Dispatchers.IO) {
                val source: suspend (CredentialRequest) -> Credential? = {
                    if (it.host == "github.com") token else null
                }
                CredentialSocket.serve(selector, path, source, { synchronized(logs) { logs += it } }) {
                    ready.complete(Unit)
                }
            }
            ready.await()
            val connect = suspend { AgentSocket.connect(selector, path) }
            assertEquals(token, CredentialHelper.ask(connect, CredentialRequest("https", "github.com")))
            assertNull(CredentialHelper.ask(connect, CredentialRequest("https", "gitlab.com")))
            credentials.cancel()
            credentials.join()
            selector.close()
            assertTrue("credentials listening" in logs)
        }
    }

    @Test
    fun stoppingTheHostStopsTheTerminalsItServes() = runBlocking {
        withTimeout(20.seconds) {
            val path = Files.createTempDirectory("ostrom-agent").resolve("agent.sock").toString()
            val selector = SelectorManager(Dispatchers.IO)
            val started = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            val host = launch(Dispatchers.IO) {
                AgentSocket.serve(selector, path, AgentHost(HangingStarter(started)), {}) { ready.complete(Unit) }
            }
            ready.await()
            val terminal = RemoteTerminalStarter({ AgentSocket.connect(selector, path) }).start("run", emptyMap(), "/")
            started.await()
            host.cancel()
            host.join()
            assertNull(terminal.read())
            terminal.close()
            selector.close()
        }
    }
}
