// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.infrastructure.Scrollback
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalRelay.ClientBound
import run.ostrom.server.runners.infrastructure.TerminalRelay.RunnerBound
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TerminalRelayTest {
    private val relay = TerminalRelay(scrollbackBytes = 8, clientQueue = 4)
    private val id = Uuid.random()

    private fun ReceiveChannel<ClientBound>.drain(): List<ClientBound> = buildList {
        while (true) add(tryReceive().getOrNull() ?: break)
    }

    private fun List<ClientBound>.text(): String = filterIsInstance<ClientBound.Output>().joinToString(
        "",
    ) { it.bytes.decodeToString() }

    @Test
    fun aClientSeesTheRunnerComeAndGoAndItsOutput() = runTest {
        val client = relay.connectClient(id)
        assertEquals(listOf<ClientBound>(ClientBound.Runner(false)), client.outbound.drain())

        val runner = assertNotNull(relay.connectRunner(id, 1))
        relay.output(id, runner, "hello".encodeToByteArray())
        val frames = client.outbound.drain()
        assertEquals(ClientBound.Runner(true), frames.first())
        assertEquals("hello", frames.text())

        relay.disconnectRunner(id, runner)
        assertEquals(listOf<ClientBound>(ClientBound.Runner(false)), client.outbound.drain())
        assertTrue(runner.outbound.isClosedForSend)
    }

    @Test
    fun aNewClientGetsTheScrollbackFirst() = runTest {
        val runner = assertNotNull(relay.connectRunner(id, 1))
        relay.output(id, runner, "0123".encodeToByteArray())
        relay.output(id, runner, "456789".encodeToByteArray())

        val frames = relay.connectClient(id).outbound.drain()
        assertEquals("23456789", frames.text())
        assertEquals(ClientBound.Runner(true), frames.last())
    }

    @Test
    fun inputAndResizeReachTheRunnerOnly() = runTest {
        assertFalse(relay.input(id, byteArrayOf(1)))
        relay.connectClient(id)
        assertFalse(relay.resize(id, 80, 24))
        val runner = assertNotNull(relay.connectRunner(id, 1))

        assertTrue(relay.input(id, "ls\r".encodeToByteArray()))
        assertTrue(relay.resize(id, 120, 40))

        val input = assertIs<RunnerBound.Input>(runner.outbound.tryReceive().getOrNull())
        assertContentEquals("ls\r".encodeToByteArray(), input.bytes)
        assertEquals(RunnerBound.Resize(120, 40), runner.outbound.tryReceive().getOrNull())
    }

    @Test
    fun aNewerExecutionReplacesTheRunnerAndAnOlderOneIsRefused() = runTest {
        val client = relay.connectClient(id)
        val first = assertNotNull(relay.connectRunner(id, 1))
        val second = assertNotNull(relay.connectRunner(id, 2))

        assertTrue(first.outbound.isClosedForSend)
        assertNull(relay.connectRunner(id, 1))
        // The replaced runner's output and departure change nothing.
        relay.output(id, first, "old".encodeToByteArray())
        assertFalse(relay.disconnectRunner(id, first), "Not the session's runner any more")
        client.outbound.drain()
        relay.output(id, second, "new".encodeToByteArray())
        assertEquals("new", client.outbound.drain().text())
        assertEquals(Triple(1, 1, 1), relay.counts())
        // The same execution may reconnect.
        assertNotNull(relay.connectRunner(id, 2))
    }

    @Test
    fun aClientThatDoesNotKeepUpIsDroppedAlone() = runTest {
        val slow = relay.connectClient(id)
        val runner = assertNotNull(relay.connectRunner(id, 1))
        val fast = relay.connectClient(id)
        repeat(6) {
            fast.outbound.drain()
            relay.output(id, runner, byteArrayOf(it.toByte()))
        }
        assertTrue(slow.outbound.isClosedForSend)
        assertFalse(fast.outbound.isClosedForSend)
        assertEquals(Triple(1, 1, 1), relay.counts())
    }

    @Test
    fun anotherReplicasHopIsServedOnlyWhileTheRunnerIsHere() = runTest {
        assertNull(relay.connectHopClient(id), "No runner here: the other replica looks again")
        assertFalse(relay.runnerHere(id))
        val member = relay.connectClient(id)
        assertNull(relay.connectHopClient(id), "Nor with only members waiting")

        val runner = assertNotNull(relay.connectRunner(id, 1))
        assertTrue(relay.runnerHere(id))
        relay.output(id, runner, "hi".encodeToByteArray())
        val hop = assertNotNull(relay.connectHopClient(id))
        assertTrue(hop.hop)
        val greeting = hop.outbound.drain()
        assertEquals("hi", greeting.text(), "The scrollback first, as any client")
        assertEquals(ClientBound.Runner(true), greeting.last())
        member.outbound.drain()

        relay.disconnectRunner(id, runner)
        assertTrue(hop.moved, "Dropped as moved, not told the runner left")
        assertTrue(hop.outbound.isClosedForSend)
        assertEquals(emptyList(), hop.outbound.drain())
        assertEquals(listOf<ClientBound>(ClientBound.Runner(false)), member.outbound.drain(), "A member is told")
        assertFalse(member.moved)
        assertEquals(1, relay.counts().third)
    }

    @Test
    fun anEmptyRoomIsForgotten() = runTest {
        val client = relay.connectClient(id)
        assertEquals(Triple(1, 0, 1), relay.counts())
        val runner = assertNotNull(relay.connectRunner(id, 1))
        relay.disconnectClient(id, client)
        assertEquals(Triple(1, 1, 0), relay.counts())
        assertTrue(relay.disconnectRunner(id, runner))
        assertEquals(Triple(0, 0, 0), relay.counts())
        // Unknown rooms are ignored.
        relay.disconnectClient(id, client)
        assertFalse(relay.disconnectRunner(id, runner))
        relay.output(id, runner, byteArrayOf(1))
        assertEquals(Triple(0, 0, 0), relay.counts())
    }

    @Test
    fun theScrollbackKeepsTheLastBytes() {
        val scrollback = Scrollback(4)
        assertEquals(0, scrollback.snapshot().size)
        scrollback.append("ab".encodeToByteArray())
        assertEquals("ab", scrollback.snapshot().decodeToString())
        scrollback.append("cde".encodeToByteArray())
        assertEquals("bcde", scrollback.snapshot().decodeToString())
        scrollback.append("0123456789".encodeToByteArray())
        assertEquals("6789", scrollback.snapshot().decodeToString())
    }

    @Test
    fun theDefaultsAreSized() {
        assertEquals(256 * 1024, TerminalRelay.DEFAULT_SCROLLBACK_BYTES)
        assertEquals(256, TerminalRelay.DEFAULT_CLIENT_QUEUE)
    }
}
