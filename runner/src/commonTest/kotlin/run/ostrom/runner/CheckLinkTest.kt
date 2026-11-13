// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A connection whose two ends the test holds: [fromPeer] is what the holder reads, [toPeer] what it writes. */
internal class LinkPipe : AgentConnection {
    val fromPeer = ByteChannel()
    val toPeer = ByteChannel()
    var closed = false
    override val input: ByteReadChannel = fromPeer
    override val output: ByteWriteChannel = toPeer

    override fun close() {
        closed = true
    }
}

class CheckLinkTest {
    @Test
    fun aCheckRunsOnANewConnectionThatOpensWithTheRunnersNonce() = runTest {
        val link = CheckLink()
        val linked = LinkPipe()
        link.accept(linked)
        val started = async { link.start("npm test", mapOf("CI" to "1"), "/workspace") }
        // The check host reads the nonce on the link, then opens the check's connection with it.
        val nonce = linked.toPeer.readByteArray(NONCE_LENGTH).decodeToString()
        val check = LinkPipe()
        check.fromPeer.writeByteArray(nonce.encodeToByteArray())
        check.fromPeer.flush()
        link.accept(check)
        val terminal = started.await()
        assertEquals(AgentFrame.Start("npm test", mapOf("CI" to "1"), "/workspace"), AgentFrames.read(check.toPeer))
        AgentFrames.write(check.fromPeer, AgentFrame.Exit(0))
        assertNull(terminal.read())
        assertEquals(0, terminal.exitCode)
        assertFalse(linked.closed, "The link stays open for the next check.")
        // The nonce served once: the same one again is nobody's.
        val replay = LinkPipe()
        replay.fromPeer.writeByteArray(nonce.encodeToByteArray())
        replay.fromPeer.flush()
        link.accept(replay)
        assertTrue(replay.closed)
    }

    @Test
    fun aConnectionWithoutANonceTheRunnerAskedForIsClosed() = runTest {
        val link = CheckLink(answer = 1.seconds)
        link.accept(LinkPipe())
        val unknown = opening("0123456789abcdef0123456789abcdef".encodeToByteArray())
        val silent = LinkPipe()
        val ended = opening(ByteArray(0), close = true)
        val short = opening("0123".encodeToByteArray(), close = true)
        val partial = opening("0123".encodeToByteArray())
        val all = listOf(unknown, silent, ended, short, partial)
        all.forEach { link.accept(it) }
        all.forEach { assertTrue(it.closed) }
    }

    @Test
    fun aCheckFailsAsUnreachableWithoutTheLinkOrWithoutAnAnswer() = runTest {
        val unlinked = CheckLink(wait = 1.seconds)
        assertFailsWith<CheckHostUnreachableException> { unlinked.start("true", emptyMap(), "/workspace") }
        val silent = CheckLink(answer = 1.seconds)
        val linked = LinkPipe()
        silent.accept(linked)
        assertFailsWith<CheckHostUnreachableException> { silent.start("true", emptyMap(), "/workspace") }
        // The connection that comes too late is nobody's.
        val late = opening(linked.toPeer.readByteArray(NONCE_LENGTH))
        silent.accept(late)
        assertTrue(late.closed)
    }

    @Test
    fun aConnectionIsHandedToTheCheckStillWaitingForItOnly() {
        val waiting = CompletableDeferred<AgentConnection>()
        val taken = LinkPipe()
        CheckLink.hand(waiting, taken)
        assertFalse(taken.closed)
        val second = LinkPipe()
        CheckLink.hand(waiting, second)
        val unasked = LinkPipe()
        CheckLink.hand(null, unasked)
        assertTrue(second.closed)
        assertTrue(unasked.closed)
    }

    @Test
    fun aConnectionThatCameAfterTheCheckGaveUpIsClosed() {
        val late = LinkPipe()
        CheckLink.settle(CompletableDeferred(late), null)
        assertTrue(late.closed)
        val used = LinkPipe()
        CheckLink.settle(CompletableDeferred(used), used)
        assertFalse(used.closed)
        val never = CompletableDeferred<AgentConnection>()
        CheckLink.settle(never, null)
        assertTrue(never.isCompleted)
    }

    @Test
    fun aNonceIsThirtyTwoLowercaseHexadecimalCharacters() = runTest {
        assertEquals(VALID, CheckNonces.read(channel(VALID)))
        for (bad in listOf("-", "Z", "z")) {
            assertNull(CheckNonces.read(channel(bad + VALID.drop(1))), bad)
        }
        assertNull(CheckNonces.read(ByteReadChannel.Empty))
        assertNull(CheckNonces.read(channel("0123")))
    }

    private suspend fun opening(bytes: ByteArray, close: Boolean = false): LinkPipe = LinkPipe().apply {
        fromPeer.writeByteArray(bytes)
        fromPeer.flush()
        if (close) fromPeer.flushAndClose()
    }

    private fun channel(text: String): ByteReadChannel = ByteReadChannel(text.encodeToByteArray())

    private companion object {
        const val NONCE_LENGTH = 32
        const val VALID = "0123456789abcdef0123456789abcdef"
    }
}

class CheckHostTest {
    @Test
    fun theCheckHostRunsEachCheckOnItsOwnConnectionOpenedWithTheNonce() = runTest {
        val link = LinkPipe()
        val connections = mutableListOf<LinkPipe>()
        val logs = mutableListOf<String>()
        var attempts = 0
        val host = CheckHost(
            AgentHost(RecordingStarter(ScriptedTerminal())),
            {
                attempts++
                if (attempts == 2) error("refused")
                // A connection on which the runner never starts anything: the host serves it and moves on.
                LinkPipe().also { it.fromPeer.flushAndClose() }.also { connections += it }
            },
            { logs += it },
        )
        for (nonce in listOf(FIRST, SECOND, THIRD)) link.fromPeer.writeByteArray(nonce.encodeToByteArray())
        link.fromPeer.flushAndClose()
        host.serve(link)
        assertEquals(listOf(FIRST, THIRD), connections.map { it.toPeer.readByteArray(32).decodeToString() })
        assertTrue(connections.all { it.closed })
        assertEquals(listOf("check connection failed: IllegalStateException"), logs)
    }

    @Test
    fun theCheckHostLetsACancellationThrough() = runTest {
        val link = LinkPipe()
        link.fromPeer.writeByteArray(FIRST.encodeToByteArray())
        link.fromPeer.flushAndClose()
        val stopped: suspend () -> AgentConnection = { throw CancellationException("stop") }
        val host = CheckHost(AgentHost(RecordingStarter(ScriptedTerminal())), stopped) {}
        assertFailsWith<CancellationException> { host.serve(link) }
    }

    private companion object {
        const val FIRST = "11111111111111111111111111111111"
        const val SECOND = "22222222222222222222222222222222"
        const val THIRD = "33333333333333333333333333333333"
    }
}

class CheckTerminalsTest {
    private class Homes : CheckHomes {
        val made = mutableListOf<String>()
        val released = mutableListOf<String>()

        override fun create(): String = "/tmp/check-${made.size + 1}".also { made += it }

        override fun release(home: String) {
            released += home
        }
    }

    @Test
    fun eachCheckHasANewHomeReleasedWhenItEnds() = runTest {
        val homes = Homes()
        val terminal = ScriptedTerminal()
        val starter = RecordingStarter(terminal)
        val checks = CheckTerminals(starter, homes, "/workspace") { it }
        val first = checks.start("true", mapOf("HOME" to "/tmp", "TMPDIR" to "/tmp", "CI" to "1"), "/workspace")
        assertEquals(mapOf("HOME" to "/tmp/check-1", "TMPDIR" to "/tmp/check-1", "CI" to "1"), starter.starts[0].second)
        assertTrue(homes.released.isEmpty())
        first.close()
        assertTrue(terminal.closed)
        assertEquals(listOf("/tmp/check-1"), homes.released)
        checks.start("true", emptyMap(), "/workspace").close()
        assertEquals("/tmp/check-2", starter.starts[1].second["HOME"])
        assertEquals(listOf("/tmp/check-1", "/tmp/check-2"), homes.released)
    }

    @Test
    fun aCheckRunsInTheRealFolderOfItsPathAndNeverOutsideTheWorkspace() = runTest {
        val starter = RecordingStarter(ScriptedTerminal())
        // A real folder, the workspace itself, a link to `/`, a sibling sharing the prefix, a folder that is not there.
        val links = mapOf(
            "/workspace/app" to "/workspace/app",
            "/workspace/up" to "/",
            "/workspace/near" to "/workspacex",
        )
        val checks = CheckTerminals(starter, Homes(), "/workspace") { path ->
            links[path] ?: path.takeIf { it == "/workspace" }
        }
        checks.start("npm test", emptyMap(), "/workspace/app")
        checks.start("npm test", emptyMap(), "/workspace")
        checks.start("npm test", emptyMap(), "/workspace/up")
        checks.start("npm test", emptyMap(), "/workspace/near")
        checks.start("npm test", emptyMap(), "/workspace/missing")
        val folders = listOf("/workspace/app", "/workspace", "/workspace", "/workspace", "/workspace")
        assertEquals(folders, starter.starts.map { it.third })
        assertEquals(listOf(true, true, false, false, false), starter.starts.map { it.first == "npm test" })
        assertTrue(starter.starts.drop(2).all { "exit 126" in it.first })
    }

    @Test
    fun aCheckThatDoesNotStartReleasesItsHome() = runTest {
        val homes = Homes()
        val failing = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                error("no shell")
        }
        val failingChecks = CheckTerminals(failing, homes, "/workspace") { it }
        assertFailsWith<IllegalStateException> { failingChecks.start("true", emptyMap(), "/workspace") }
        assertEquals(listOf("/tmp/check-1"), homes.released)
        val stopped = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                throw CancellationException("stop")
        }
        val stoppedChecks = CheckTerminals(stopped, homes, "/workspace") { it }
        assertFailsWith<CancellationException> { stoppedChecks.start("true", emptyMap(), "/workspace") }
        assertEquals(listOf("/tmp/check-1", "/tmp/check-2"), homes.released)
    }
}
