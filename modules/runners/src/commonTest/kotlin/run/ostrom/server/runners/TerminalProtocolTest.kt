// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.presentation.TerminalCloseCodes
import run.ostrom.server.runners.presentation.TerminalControl
import run.ostrom.server.runners.presentation.TerminalJson
import run.ostrom.server.runners.presentation.closeReason
import run.ostrom.server.runners.presentation.forwardToClient
import run.ostrom.server.runners.presentation.forwardToRunner
import run.ostrom.server.runners.presentation.installTerminalWebSockets
import io.ktor.server.application.pluginOrNull
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The terminal protocol's pieces that need no socket: the queues' pumps, close reasons, the plugin. */
class TerminalProtocolTest {
    @Test
    fun theRunnerReceivesInputAndResizesUntilANewerRunnerClosesItsQueue() = runTest {
        val queue = Channel<TerminalRelay.RunnerBound>(Channel.UNLIMITED)
        queue.send(TerminalRelay.RunnerBound.Input("ls".encodeToByteArray()))
        queue.send(TerminalRelay.RunnerBound.Resize(80, 24))
        queue.close()
        val sent = mutableListOf<Frame>()
        forwardToRunner(queue) { sent += it }

        assertEquals("ls", assertIs<Frame.Binary>(sent[0]).readBytes().decodeToString())
        val resize = TerminalJson.decodeFromString(
            TerminalControl.serializer(),
            assertIs<Frame.Text>(sent[1]).readText(),
        )
        assertEquals(TerminalControl.Resize(80, 24), resize)
        assertEquals(TerminalCloseCodes.CONFLICT, assertIs<Frame.Close>(sent[2]).readReason()?.code)
    }

    @Test
    fun aClientReceivesOutputAndTheRunnerStateUntilItIsDroppedForFallingBehind() = runTest {
        val queue = Channel<TerminalRelay.ClientBound>(Channel.UNLIMITED)
        queue.send(TerminalRelay.ClientBound.Output("$ ".encodeToByteArray()))
        queue.send(TerminalRelay.ClientBound.Runner(true))
        queue.close()
        val sent = mutableListOf<Frame>()
        forwardToClient(queue) { sent += it }

        assertEquals("$ ", assertIs<Frame.Binary>(sent[0]).readBytes().decodeToString())
        val state = TerminalJson.decodeFromString(
            TerminalControl.serializer(),
            assertIs<Frame.Text>(sent[1]).readText(),
        )
        assertEquals(TerminalControl.Runner(true), state)
        assertEquals(TerminalCloseCodes.TOO_SLOW, assertIs<Frame.Close>(sent[2]).readReason()?.code)
    }

    @Test
    fun everyRefusalHasACloseCode() {
        val expected = mapOf(
            ErrorKind.UNAUTHORIZED to TerminalCloseCodes.UNAUTHORIZED,
            ErrorKind.FORBIDDEN to TerminalCloseCodes.FORBIDDEN,
            ErrorKind.NOT_FOUND to TerminalCloseCodes.NOT_FOUND,
            ErrorKind.CONFLICT to TerminalCloseCodes.CONFLICT,
        )
        for (kind in ErrorKind.entries) {
            val reason = closeReason(OstromException(kind, "why"))
            assertEquals(expected[kind] ?: TerminalCloseCodes.BAD_REQUEST, reason.code, kind.name)
            assertEquals("why", reason.message)
        }
    }

    @Test
    fun webSocketsAreInstalledOnceWhicheverModuleComesFirst() = testApplication {
        application {
            installTerminalWebSockets()
            installTerminalWebSockets()
            assertNotNull(pluginOrNull(WebSockets))
        }
        startApplication()
    }
}
