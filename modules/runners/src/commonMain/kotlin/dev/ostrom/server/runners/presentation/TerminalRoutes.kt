package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.CloudSessionNotFoundException
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import dev.ostrom.server.runners.domain.OpenTerminalUseCase
import dev.ostrom.server.runners.domain.TerminalActivity
import dev.ostrom.server.runners.domain.TerminalTickets
import dev.ostrom.server.runners.infrastructure.TerminalRelay
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * What the terminal routes need.
 *
 * @property relay The terminal relay.
 * @property openTerminal Checks a member may attach.
 * @property issueTicket Issues browser tickets.
 * @property tickets Redeems them.
 * @property authenticateRunner Checks a runner's token.
 * @property authenticator Resolves members' bearer tokens (the CLI sends one on the upgrade).
 * @property activity Records terminal input as session activity.
 */
data class TerminalRoutesDependencies(
    val relay: TerminalRelay,
    val openTerminal: OpenTerminalUseCase,
    val issueTicket: IssueTerminalTicketUseCase,
    val tickets: TerminalTickets,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val authenticator: TokenAuthenticator,
    val activity: TerminalActivity,
)

/** A text frame of the terminal protocol (binary frames carry the terminal's bytes). */
@Serializable
sealed interface TerminalControl {
    /**
     * The terminal's size, from a client to the runner.
     *
     * @property columns Columns, 1 to 1000.
     * @property rows Rows, 1 to 1000.
     */
    @Serializable
    @SerialName("resize")
    data class Resize(val columns: Int, val rows: Int) : TerminalControl

    /**
     * Whether the runner is connected, from the server to clients.
     *
     * @property connected The runner's state.
     */
    @Serializable
    @SerialName("runner")
    data class Runner(val connected: Boolean) : TerminalControl
}

/** The browser ticket. */
@Serializable
data class TerminalTicketResponse(val ticket: String, val expiresAt: String)

/** The close codes of the terminal WebSockets (4000 + the HTTP status they stand for). */
object TerminalCloseCodes {
    const val BAD_REQUEST: Short = 4400
    const val UNAUTHORIZED: Short = 4401
    const val FORBIDDEN: Short = 4403
    const val NOT_FOUND: Short = 4404
    const val TOO_SLOW: Short = 4408
    const val CONFLICT: Short = 4409
}

/** Terminal frames serializer: the `type` field names the frame. */
val TerminalJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

private const val MAX_SIZE = 1000

/**
 * The terminal routes:
 * - `POST /api/v1/cloud/sessions/{id}/terminal/ticket`: a single-use ticket for a browser;
 * - `WS /api/v1/cloud/sessions/{id}/terminal`: a member attaches (bearer token, or `?ticket=`); binary frames are the
 *   terminal's bytes both ways, text frames are [TerminalControl];
 * - `WS /api/v1/cloud/runner/terminal?sessionId=&epoch=`: the runner's PTY, authenticated by its runner token.
 *
 * Refusals close the socket with [TerminalCloseCodes].
 *
 * @param dependencies The relay and use cases.
 */
fun Route.terminalRoutes(dependencies: TerminalRoutesDependencies) = with(dependencies) {
    if (application.pluginOrNull(WebSockets) == null) {
        application.install(WebSockets) {
            pingPeriod = 20.seconds
            timeout = 60.seconds
            maxFrameSize = MAX_FRAME_BYTES
        }
    }
    authenticated {
        post<CloudSessionsApi.Id.Terminal.Ticket> { params ->
            val caller = call.requirePrincipal().toCaller(CLOUD_WRITE)
            val id = Uuid.parseOrNull(params.parent.parent.id) ?: throw CloudSessionNotFoundException()
            val ticket = issueTicket(caller, id)
            call.respond(TerminalTicketResponse(ticket.value, ticket.expiresAt.toString()))
        }
    }
    webSocket("/api/v1/cloud/sessions/{id}/terminal") {
        val id = Uuid.parseOrNull(call.parameters["id"].orEmpty())
            ?: return@webSocket close(CloseReason(TerminalCloseCodes.NOT_FOUND, "No such cloud session."))
        val caller = try {
            clientCaller(dependencies, id)
        } catch (error: OstromException) {
            return@webSocket close(closeReason(error))
        } ?: return@webSocket close(CloseReason(TerminalCloseCodes.UNAUTHORIZED, "A valid bearer token or ticket is required."))
        try {
            openTerminal(caller, id)
        } catch (error: OstromException) {
            return@webSocket close(closeReason(error))
        }
        attachClient(dependencies, id)
    }
    webSocket("/api/v1/cloud/runner/terminal") {
        val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val id = Uuid.parseOrNull(call.parameters["sessionId"].orEmpty())
        val epoch = call.parameters["epoch"]?.toLongOrNull()
        if (token.isNullOrEmpty() || id == null || epoch == null) {
            return@webSocket close(CloseReason(TerminalCloseCodes.UNAUTHORIZED, "A runner token, sessionId and epoch are required."))
        }
        try {
            authenticateRunner(token, id, epoch)
        } catch (error: OstromException) {
            return@webSocket close(closeReason(error))
        }
        val link = relay.connectRunner(id, epoch)
            ?: return@webSocket close(CloseReason(TerminalCloseCodes.CONFLICT, "A newer execution holds this terminal."))
        try {
            coroutineScope {
                val writer = launch {
                    for (frame in link.outbound) {
                        when (frame) {
                            is TerminalRelay.RunnerBound.Input -> send(Frame.Binary(true, frame.bytes))
                            is TerminalRelay.RunnerBound.Resize -> send(Frame.Text(TerminalJson.encodeToString(TerminalControl.serializer(), TerminalControl.Resize(frame.columns, frame.rows))))
                        }
                    }
                    close(CloseReason(TerminalCloseCodes.CONFLICT, "A newer execution holds this terminal."))
                }
                for (frame in incoming) {
                    if (frame is Frame.Binary) relay.output(id, link, frame.readBytes())
                }
                writer.cancel()
            }
        } finally {
            relay.disconnectRunner(id, link)
        }
    }
}

/** 1 MiB: a pasted file, never more. */
private const val MAX_FRAME_BYTES = 1024L * 1024

private suspend fun DefaultWebSocketServerSession.clientCaller(dependencies: TerminalRoutesDependencies, id: Uuid): Caller? {
    val ticket = call.parameters["ticket"]
    if (ticket != null) return dependencies.tickets.redeem(ticket, id)
    val header = call.request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim().takeIf { it != header && it.isNotEmpty() } ?: return null
    return dependencies.authenticator.authenticate(token)?.toCaller(CLOUD_WRITE)
}

private suspend fun DefaultWebSocketServerSession.attachClient(dependencies: TerminalRoutesDependencies, id: Uuid) {
    val relay = dependencies.relay
    val link = relay.connectClient(id)
    try {
        coroutineScope {
            val writer = launch {
                for (frame in link.outbound) {
                    when (frame) {
                        is TerminalRelay.ClientBound.Output -> send(Frame.Binary(true, frame.bytes))
                        is TerminalRelay.ClientBound.Runner -> send(Frame.Text(TerminalJson.encodeToString(TerminalControl.serializer(), TerminalControl.Runner(frame.connected))))
                    }
                }
                close(CloseReason(TerminalCloseCodes.TOO_SLOW, "This terminal did not keep up with the output: attach again."))
            }
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        relay.input(id, frame.readBytes())
                        dependencies.activity.touch(id)
                    }
                    is Frame.Text -> resize(relay, id, frame.readText())
                    else -> Unit
                }
            }
            writer.cancel()
        }
    } finally {
        relay.disconnectClient(id, link)
    }
}

private suspend fun DefaultWebSocketServerSession.resize(relay: TerminalRelay, id: Uuid, text: String) {
    val control = try {
        TerminalJson.decodeFromString(TerminalControl.serializer(), text)
    } catch (error: CancellationException) {
        throw error
    } catch (_: SerializationException) {
        return close(CloseReason(TerminalCloseCodes.BAD_REQUEST, "Unknown terminal frame."))
    } catch (_: IllegalArgumentException) {
        return close(CloseReason(TerminalCloseCodes.BAD_REQUEST, "Unknown terminal frame."))
    }
    if (control is TerminalControl.Resize && control.columns in 1..MAX_SIZE && control.rows in 1..MAX_SIZE) {
        relay.resize(id, control.columns, control.rows)
    }
}

private fun closeReason(error: OstromException): CloseReason {
    val code = when (error.kind) {
        ErrorKind.UNAUTHORIZED -> TerminalCloseCodes.UNAUTHORIZED
        ErrorKind.FORBIDDEN -> TerminalCloseCodes.FORBIDDEN
        ErrorKind.NOT_FOUND -> TerminalCloseCodes.NOT_FOUND
        ErrorKind.CONFLICT -> TerminalCloseCodes.CONFLICT
        else -> TerminalCloseCodes.BAD_REQUEST
    }
    return CloseReason(code, error.message.orEmpty())
}
