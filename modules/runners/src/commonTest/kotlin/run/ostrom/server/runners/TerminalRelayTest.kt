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

    /** What a client was sent, its cursors left out (they have their own tests). */
    private fun ReceiveChannel<ClientBound>.drain(): List<ClientBound> = drainAll().filter { it !is ClientBound.Joined }

    private fun ReceiveChannel<ClientBound>.drainAll(): List<ClientBound> = buildList {
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
    fun aMemberComingBackToTheSameRoomGetsOnlyWhatTheyHaveNotSeen() = runTest {
        val relay = TerminalRelay(scrollbackBytes = 16)
        val runner = assertNotNull(relay.connectRunner(id, 1))
        relay.output(id, runner, "abc".encodeToByteArray())
        val first = assertNotNull(relay.connectHopClient(id)).outbound.drainAll()
        val joined = assertIs<ClientBound.Joined>(first[1])
        assertEquals("abc", first.text())
        assertEquals(3L, joined.cursor.offset)

        // The hop was dropped (a socket lost), the runner went on: the member comes back with their cursor.
        relay.output(id, runner, "def".encodeToByteArray())
        val again = assertNotNull(relay.connectHopClient(id, joined.cursor)).outbound.drainAll()
        assertEquals("def", again.text(), "Never abc twice")
        assertEquals(ClientBound.Joined(TerminalRelay.Cursor(joined.cursor.room, 6)), again[1])

        val upToDate = relay.connectClient(id, TerminalRelay.Cursor(joined.cursor.room, 6)).outbound.drainAll()
        assertEquals(emptyList(), upToDate.filterIsInstance<ClientBound.Output>(), "Nothing missed, nothing sent")
    }

    @Test
    fun aMemberFromAnotherRoomOrFromFurtherBackIsResetBeforeTheScrollback() = runTest {
        val runner = assertNotNull(relay.connectRunner(id, 1))
        relay.output(id, runner, "0123456789".encodeToByteArray())
        val reset = TerminalRelay.RESET.decodeToString()

        val elsewhere = relay.connectClient(id, TerminalRelay.Cursor(Uuid.random(), 4)).outbound.drain()
        assertEquals(reset + "23456789", elsewhere.text(), "Another room: the screen is drawn again")
        val room = assertIs<ClientBound.Joined>(relay.connectClient(id).outbound.drainAll()[1]).cursor.room
        val behind = relay.connectClient(id, TerminalRelay.Cursor(room, 1)).outbound.drain()
        assertEquals(reset + "23456789", behind.text(), "Past the scrollback: a gap, drawn again")
        val ahead = relay.connectClient(id, TerminalRelay.Cursor(room, 11)).outbound.drain()
        assertEquals(reset + "23456789", ahead.text(), "A cursor ahead of the room is not one of its places")
    }

    @Test
    fun aCursorReadsAsItIsWritten() {
        val cursor = TerminalRelay.Cursor(Uuid.random(), 42)
        assertEquals(cursor, TerminalRelay.Cursor.parse(cursor.toString()))
        assertNull(TerminalRelay.Cursor.parse(null))
        assertNull(TerminalRelay.Cursor.parse("nothing"))
        assertNull(TerminalRelay.Cursor.parse("not-a-uuid.4"))
        assertNull(TerminalRelay.Cursor.parse("${cursor.room}.many"))
        assertNull(TerminalRelay.Cursor.parse("${cursor.room}.-1"))
    }

    @Test
    fun theScrollbackSaysWhatFollowsAnOffset() {
        val scrollback = Scrollback(4)
        scrollback.append("abcdef".encodeToByteArray())
        assertEquals(6L, scrollback.total)
        assertTrue(scrollback.reaches(2) && scrollback.reaches(6))
        assertFalse(scrollback.reaches(1) || scrollback.reaches(7))
        assertEquals("ef", scrollback.since(4).decodeToString())
        assertEquals("", scrollback.since(6).decodeToString())
    }

    @Test
    fun theDefaultsAreSized() {
        assertEquals(256 * 1024, TerminalRelay.DEFAULT_SCROLLBACK_BYTES)
        assertEquals(256, TerminalRelay.DEFAULT_CLIENT_QUEUE)
    }
}
