package dev.ostrom.server.runners

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.presentation.TerminalCloseCodes
import dev.ostrom.server.runners.presentation.TerminalControl
import dev.ostrom.server.runners.presentation.TerminalJson
import dev.ostrom.server.runners.presentation.TerminalTicketResponse
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The terminal WebSockets against a real server and database. */
class TerminalRoutesTest {
    private val identities = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                    "alice" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("org:$ORG_A"))
                    "carol" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("org:$ORG_B"))
                    "reader" -> AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("org:$ORG_A", "cloud:read"))
                    else -> null
                }
            }
        }
    }

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    private suspend fun DefaultClientWebSocketSession.closeCode(): Short? = closeReason.await()?.code

    private suspend fun DefaultClientWebSocketSession.nextControl(): TerminalControl =
        TerminalJson.decodeFromString(TerminalControl.serializer(), (incoming.receive() as Frame.Text).readText())

    @Test
    fun aMemberAndTheRunnerTalkThroughTheRelay() = serverTest(listOf(RunnersModule), identities) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        val token = tokens.mint()
        val running = session(state = SessionState.RUNNING, createdAt = t0).copy(epoch = 2, runnerTokenHash = tokens.hash(token))
        repository.createWithinCap(running, 5)
        val ws: HttpClient = createClient { install(WebSockets) }

        ws.webSocket("/v1/runner/terminal?sessionId=${running.id}&epoch=2", request = { bearerAuth(token) }) {
            val runner = this
            ws.webSocket("/v1/cloud/sessions/${running.id}/terminal", request = { bearerAuth("alice") }) {
                assertEquals(TerminalControl.Runner(true), nextControl())
                runner.send(Frame.Binary(true, "$ ".encodeToByteArray()))
                assertEquals("$ ", (incoming.receive() as Frame.Binary).readBytes().decodeToString())

                send(Frame.Binary(true, "ls\r".encodeToByteArray()))
                assertEquals("ls\r", (runner.incoming.receive() as Frame.Binary).readBytes().decodeToString())
                send("""{"type":"resize","columns":120,"rows":40}""")
                assertEquals(TerminalControl.Resize(120, 40), TerminalJson.decodeFromString(TerminalControl.serializer(), (runner.incoming.receive() as Frame.Text).readText()))
                // Out-of-range sizes are ignored; an unknown frame closes the socket.
                send("""{"type":"resize","columns":0,"rows":40}""")
                send("""{"type":"runner","connected":false}""")
                send("""{"type":"dance"}""")
                assertEquals(TerminalCloseCodes.BAD_REQUEST, closeCode())
            }
            // The scrollback greets the next member.
            ws.webSocket("/v1/cloud/sessions/${running.id}/terminal", request = { bearerAuth("alice") }) {
                assertEquals("$ ", (incoming.receive() as Frame.Binary).readBytes().decodeToString())
                assertEquals(TerminalControl.Runner(true), nextControl())
            }
        }
        // Typing counts as activity.
        assertNotNull(repository.getById(running.id)?.lastActivityAt)
    }

    @Test
    fun aBrowserOpensTheTerminalWithASingleUseTicket() = serverTest(listOf(RunnersModule), identities) { server ->
        val live = session(state = SessionState.RUNNING, createdAt = t0)
        server.koin.get<CloudSessionsRepository>().createWithinCap(live, 5)
        val ticket = server.client.post("/v1/cloud/sessions/${live.id}/terminal/ticket") { bearerAuth("alice") }.body<TerminalTicketResponse>()
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/v1/cloud/sessions/${live.id}/terminal?ticket=${ticket.ticket}") {
            assertEquals(TerminalControl.Runner(false), nextControl())
        }
        ws.webSocket("/v1/cloud/sessions/${live.id}/terminal?ticket=${ticket.ticket}") {
            assertEquals(TerminalCloseCodes.UNAUTHORIZED, closeCode())
        }
        assertEquals(HttpStatusCode.Forbidden, server.client.post("/v1/cloud/sessions/${live.id}/terminal/ticket") { bearerAuth("reader") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.post("/v1/cloud/sessions/${live.id}/terminal/ticket") { bearerAuth("carol") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.post("/v1/cloud/sessions/nope/terminal/ticket") { bearerAuth("alice") }.status)
    }

    @Test
    fun theTerminalRefusesStrangersAndOverSessions() = serverTest(listOf(RunnersModule), identities) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val live = session(state = SessionState.RUNNING, createdAt = t0)
        val over = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED, createdAt = t0)
        repository.createWithinCap(live, 5)
        repository.createWithinCap(over, 5)
        val ws = createClient { install(WebSockets) }

        suspend fun code(path: String, token: String?): Short? {
            var code: Short? = null
            ws.webSocket(path, request = { if (token != null) header(HttpHeaders.Authorization, token) }) { code = closeCode() }
            return code
        }
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("/v1/cloud/sessions/${live.id}/terminal", null))
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("/v1/cloud/sessions/${live.id}/terminal", "Bearer unknown"))
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("/v1/cloud/sessions/${live.id}/terminal", "alice"))
        assertEquals(TerminalCloseCodes.FORBIDDEN, code("/v1/cloud/sessions/${live.id}/terminal", "Bearer reader"))
        assertEquals(TerminalCloseCodes.NOT_FOUND, code("/v1/cloud/sessions/${live.id}/terminal", "Bearer carol"))
        assertEquals(TerminalCloseCodes.NOT_FOUND, code("/v1/cloud/sessions/nope/terminal", "Bearer alice"))
        assertEquals(TerminalCloseCodes.CONFLICT, code("/v1/cloud/sessions/${over.id}/terminal", "Bearer alice"))
    }

    @Test
    fun theRunnerChannelChecksTokenAndEpoch() = serverTest(listOf(RunnersModule), identities) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        val token = tokens.mint()
        val running = session(state = SessionState.RUNNING, createdAt = t0).copy(epoch = 2, runnerTokenHash = tokens.hash(token))
        repository.createWithinCap(running, 5)
        val ws = createClient { install(WebSockets) }

        suspend fun code(query: String, auth: String?): Short? {
            var code: Short? = null
            ws.webSocket("/v1/runner/terminal$query", request = { if (auth != null) header(HttpHeaders.Authorization, auth) }) { code = closeCode() }
            return code
        }
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("?sessionId=${running.id}&epoch=2", null))
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("?sessionId=${running.id}", "Bearer $token"))
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("?sessionId=nope&epoch=2", "Bearer $token"))
        assertEquals(TerminalCloseCodes.UNAUTHORIZED, code("?sessionId=${running.id}&epoch=2", "Bearer ostrom_runner_wrong"))
        assertEquals(TerminalCloseCodes.CONFLICT, code("?sessionId=${running.id}&epoch=1", "Bearer $token"))
        assertEquals(TerminalCloseCodes.CONFLICT, code("?sessionId=${Uuid.random()}&epoch=2", "Bearer $token"))
    }
}
