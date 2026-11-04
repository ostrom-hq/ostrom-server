// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.fakes.ALICE
import dev.ostrom.server.runners.fakes.CAROL
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.infrastructure.TerminalRelay
import dev.ostrom.server.runners.infrastructure.TerminalRelay.ClientBound
import dev.ostrom.server.runners.presentation.TerminalCloseCodes
import dev.ostrom.server.runners.presentation.TerminalTicketResponse
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.toList
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The terminal WebSockets between organizations, over a real socket (AUDIT-SECU-ISOLATION): a ticket, a member's token
 * or a runner's token of organization A never opens a session of organization B, a spent or expired ticket opens
 * nothing, and every refusal comes before any frame: not a byte of the terminal, not even whether its runner is there.
 * Carol's session (organization B) has a runner connected and output in its scrollback, so a leak would show. On the
 * JVM only: Ktor's test client has no WebSockets on native platforms.
 */
class TerminalIsolationTest {
    private val clock = SettableClock()
    private val overrides = module {
        includes(testIdentities)
        single<Clock> { clock }
    }

    /** What a refused socket saw: its close, and every frame before it. */
    private data class Attempt(val code: Short?, val reason: String?, val frames: Int)

    /** Alice's live session in organization A, and Carol's in organization B with a runner and its output. */
    private class World(
        val alice: CloudSession,
        val carol: CloudSession,
        val aliceRunner: String,
        val relay: TerminalRelay,
    )

    private suspend fun TestServer.world(): World {
        val repository = koin.get<CloudSessionsRepository>()
        val tokens = koin.get<RunnerTokens>()
        val aliceRunner = tokens.mint()
        val alice = session(ownerId = ALICE.toString(), state = SessionState.RUNNING)
            .copy(epoch = 1, runnerTokenHash = tokens.hash(aliceRunner))
        val carol = session(orgId = ORG_B, ownerId = CAROL.toString(), state = SessionState.RUNNING)
            .copy(epoch = 2, runnerTokenHash = tokens.hash(tokens.mint()))
        repository.createWithinCap(alice, 5)
        repository.createWithinCap(carol, 5)
        val relay = koin.get<TerminalRelay>()
        val link = checkNotNull(relay.connectRunner(carol.id, 2))
        relay.output(carol.id, link, SECRET.encodeToByteArray())
        return World(alice, carol, aliceRunner, relay)
    }

    private suspend fun HttpClient.attempt(path: String, authorization: String? = null): Attempt {
        var attempt: Attempt? = null
        webSocket(path, request = { authorization?.let { header(HttpHeaders.Authorization, it) } }) {
            val frames = incoming.toList().size
            val reason = closeReason.await()
            attempt = Attempt(reason?.code, reason?.message, frames)
        }
        return checkNotNull(attempt)
    }

    private fun assertRefused(code: Short, attempt: Attempt) {
        assertEquals(code, attempt.code, "close code of $attempt")
        assertEquals(0, attempt.frames, "frames before the refusal: $attempt")
    }

    private suspend fun TestServer.ticket(session: CloudSession): String =
        client.post("/api/v1/cloud/sessions/${session.id}/terminal/ticket") { bearerAuth("alice") }
            .body<TerminalTicketResponse>().ticket

    // Carol's runner still holds her relay, and her scrollback is whole: nothing the attempts did reached it.
    private suspend fun World.assertCarolUntouched() {
        val probe = relay.connectClient(carol.id)
        assertContentEquals(SECRET.encodeToByteArray(), assertIs<ClientBound.Output>(probe.outbound.receive()).bytes)
        assertEquals(ClientBound.Runner(true), probe.outbound.receive())
        relay.disconnectClient(carol.id, probe)
    }

    @Test
    fun aTicketOfOneOrganizationOpensNoSessionOfAnother() = serverTest(listOf(RunnersModule), overrides) { server ->
        val world = server.world()
        val ws = createClient { install(WebSockets) }
        val ticket = server.ticket(world.alice)

        val stolen = ws.attempt("/api/v1/cloud/sessions/${world.carol.id}/terminal?ticket=$ticket")
        assertRefused(TerminalCloseCodes.UNAUTHORIZED, stolen)
        // Exactly as a session nobody has.
        val another = server.ticket(world.alice)
        assertEquals(ws.attempt("/api/v1/cloud/sessions/${Uuid.random()}/terminal?ticket=$another"), stolen)
        // Tried on another session, the ticket is spent: it no longer opens its own.
        assertRefused(
            TerminalCloseCodes.UNAUTHORIZED,
            ws.attempt("/api/v1/cloud/sessions/${world.alice.id}/terminal?ticket=$ticket"),
        )
        world.assertCarolUntouched()
    }

    @Test
    fun aSpentOrExpiredTicketOpensNothing() = serverTest(listOf(RunnersModule), overrides) { server ->
        val world = server.world()
        val ws = createClient { install(WebSockets) }
        val path = "/api/v1/cloud/sessions/${world.alice.id}/terminal"

        val once = server.ticket(world.alice)
        ws.webSocket("$path?ticket=$once") { assertIs<Frame.Text>(incoming.receive()) }
        assertRefused(TerminalCloseCodes.UNAUTHORIZED, ws.attempt("$path?ticket=$once"))

        val late = server.ticket(world.alice)
        clock.now += TICKET_LIFETIME
        assertRefused(TerminalCloseCodes.UNAUTHORIZED, ws.attempt("$path?ticket=$late"))
    }

    @Test
    fun aRunnerTokenOpensOnlyItsOwnExecution() = serverTest(listOf(RunnersModule), overrides) { server ->
        val world = server.world()
        val ws = createClient { install(WebSockets) }
        val bearer = "Bearer ${world.aliceRunner}"

        val claimed = ws.attempt("/api/v1/cloud/runner/terminal?sessionId=${world.carol.id}&epoch=2", bearer)
        assertRefused(TerminalCloseCodes.CONFLICT, claimed)
        // Exactly as a session nobody has: the claim does not tell Carol's session exists.
        val unknown = ws.attempt("/api/v1/cloud/runner/terminal?sessionId=${Uuid.random()}&epoch=2", bearer)
        assertEquals(unknown, claimed)
        // Its own session with Carol's epoch is not its execution either.
        assertRefused(
            TerminalCloseCodes.CONFLICT,
            ws.attempt("/api/v1/cloud/runner/terminal?sessionId=${world.alice.id}&epoch=2", bearer),
        )
        world.assertCarolUntouched()
    }

    @Test
    fun aMemberWithoutTheSessionOpensNothingOfIt() = serverTest(listOf(RunnersModule), overrides) { server ->
        val world = server.world()
        val ws = createClient { install(WebSockets) }
        val alices = "/api/v1/cloud/sessions/${world.alice.id}/terminal"
        val nobodys = "/api/v1/cloud/sessions/${Uuid.random()}/terminal"

        // A member of Alice's organization who does not own the session, its admin, and another organization's owner.
        for (who in listOf("bob", "dana", "carol")) {
            val attempt = ws.attempt(alices, "Bearer $who")
            assertRefused(TerminalCloseCodes.NOT_FOUND, attempt)
            assertEquals(ws.attempt(nobodys, "Bearer $who"), attempt, who)
        }
        // Alice reads Carol's no better, and a key that only reads opens no terminal.
        assertRefused(
            TerminalCloseCodes.NOT_FOUND,
            ws.attempt("/api/v1/cloud/sessions/${world.carol.id}/terminal", "Bearer alice"),
        )
        assertRefused(TerminalCloseCodes.FORBIDDEN, ws.attempt(alices, "Bearer reader"))
        world.assertCarolUntouched()
    }

    private companion object {
        const val SECRET = "carol's secret prompt $ "

        // InMemoryTerminalTickets' lifetime, and the instant it ends.
        val TICKET_LIFETIME = 60.seconds
    }
}
