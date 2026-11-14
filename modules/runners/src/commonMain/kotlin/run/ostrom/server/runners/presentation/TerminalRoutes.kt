// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.openConnections
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.TerminalHosts
import run.ostrom.server.runners.infrastructure.KtorTerminalForwarder
import run.ostrom.server.runners.infrastructure.TerminalRelay
import io.ktor.http.HttpHeaders
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

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

    /**
     * Where a hop client now is in the output of the replica holding the runner (`TerminalRelay.Cursor`), from that
     * replica to the one relaying its member, which keeps it to come back with and never passes it on.
     *
     * @property room The room.
     * @property offset The bytes of output before what follows.
     */
    @Serializable
    @SerialName("joined")
    data class Joined(val room: String, val offset: Long) : TerminalControl
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

    /** To another replica relaying its member: the runner is not here (any more), look again where it is. */
    const val MOVED: Short = 4421
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
 * Refusals close the socket with [TerminalCloseCodes]. A GET that does not ask for the upgrade is always the same bad
 * request (`invalid_request`), before any credential is read: the socket's route only takes an upgrade.
 *
 * @param dependencies The relay and use cases.
 */
// The two WebSocket endpoints are one protocol: kept together so their close codes read side by side.
@Suppress("LongMethod")
fun Route.terminalRoutes(dependencies: TerminalRoutesDependencies) = with(dependencies) {
    application.installTerminalWebSockets()
    authenticated {
        post<CloudSessionsApi.Id.Terminal.Ticket> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_WRITE)
            val id = Uuid.parseOrNull(params.parent.parent.id) ?: throw CloudSessionNotFoundException()
            val ticket = issueTicket(caller, id)
            call.respond(TerminalTicketResponse(ticket.value, ticket.expiresAt.toString()))
        }
    }
    // A terminal stays open as long as its client keeps it: open connections, counted apart from the server's other
    // calls and from the waits (P1 memory).
    openConnections {
        webSocket("/api/v1/cloud/sessions/{id}/terminal") {
            val id = Uuid.parseOrNull(call.parameters.getOrFail("id"))
                ?: return@webSocket close(CloseReason(TerminalCloseCodes.NOT_FOUND, "No such cloud session."))
            val caller = try {
                clientCaller(dependencies, id)
            } catch (error: OstromException) {
                return@webSocket close(closeReason(error))
            } ?: return@webSocket close(CloseReason(TerminalCloseCodes.UNAUTHORIZED, NEED_MEMBER_CREDENTIAL))
            try {
                openTerminal(caller, id)
            } catch (error: OstromException) {
                return@webSocket close(closeReason(error))
            }
            if (call.parameters[KtorTerminalForwarder.HOP_PARAMETER] == "1") {
                val after = TerminalRelay.Cursor.parse(call.parameters[KtorTerminalForwarder.AFTER_PARAMETER])
                attachHop(relay, id, after)
            } else {
                attachMember(dependencies, caller, id)
            }
        }
        webSocket("/api/v1/cloud/runner/terminal") {
            val token = bearerToken()
            val id = call.parameters["sessionId"]?.let(Uuid::parseOrNull)
            val epoch = call.parameters["epoch"]?.toLongOrNull()
            if (token == null || id == null || epoch == null) {
                return@webSocket close(CloseReason(TerminalCloseCodes.UNAUTHORIZED, NEED_RUNNER_CREDENTIAL))
            }
            val session = try {
                authenticateRunner(token, id, epoch)
            } catch (error: OstromException) {
                return@webSocket close(closeReason(error))
            }
            val link = relay.connectRunner(id, epoch)
                ?: return@webSocket close(CloseReason(TerminalCloseCodes.CONFLICT, NEWER_EXECUTION))
            attachRunner(dependencies, session.orgId, id, link)
        }
    }
    get("/api/v1/cloud/sessions/{id}/terminal") { throw InvalidCloudSessionRequestException(NEED_UPGRADE) }
    get("/api/v1/cloud/runner/terminal") { throw InvalidCloudSessionRequestException(NEED_UPGRADE) }
}

/**
 * Pumps a runner's PTY: its output to the relay, the clients' input and resizes to it. With other replicas, this one
 * holds the session in [TerminalHosts] while the socket is open, so that members attached elsewhere are relayed here.
 */
private suspend fun DefaultWebSocketServerSession.attachRunner(
    dependencies: TerminalRoutesDependencies,
    orgId: Uuid,
    id: Uuid,
    link: TerminalRelay.RunnerLink,
) {
    val relay = dependencies.relay
    val replica = dependencies.replica
    val hosts = replica?.hosts
    try {
        hosts?.let { quietly { it.hold(orgId, id, link.epoch) } }
        coroutineScope {
            val writer = launch { forwardToRunner(link.outbound) { send(it) } }
            val renewing = replica?.let {
                launch {
                    while (true) {
                        delay(it.renewEvery)
                        quietly { it.hosts.renew(orgId, id, link.epoch) }
                    }
                }
            }
            for (frame in incoming) {
                if (frame is Frame.Binary) relay.output(id, link, frame.readBytes())
            }
            writer.cancel()
            renewing?.cancel()
        }
    } finally {
        withContext(NonCancellable) {
            // A socket another replaced (the runner came back here) leaves the hold to its successor; one the database
            // could not give back ends with its lease.
            val current = relay.disconnectRunner(id, link)
            if (current) hosts?.let { quietly { it.release(orgId, id, link.epoch) } }
        }
    }
}

private const val NEED_MEMBER_CREDENTIAL = "A valid bearer token or ticket is required."
private const val NEED_RUNNER_CREDENTIAL = "A runner token, sessionId and epoch are required."
private const val NEED_UPGRADE = "A terminal is a WebSocket: ask for the upgrade."
private suspend fun DefaultWebSocketServerSession.clientCaller(
    dependencies: TerminalRoutesDependencies,
    id: Uuid,
): Caller? {
    val ticket = call.parameters["ticket"]
    if (ticket != null) return dependencies.tickets.redeem(ticket, id)
    val principal = bearerToken()?.let { dependencies.authenticator.authenticate(it) }
    return principal?.let { dependencies.orgResolver.callerOf(it, CLOUD_WRITE) }
}

/** The upgrade's `Authorization: Bearer` token, or null. */
private fun DefaultWebSocketServerSession.bearerToken(): String? {
    val header = call.request.headers[HttpHeaders.Authorization]
    return if (header != null && header.startsWith(BEARER)) header.substring(BEARER.length).trim() else null
}

private const val BEARER = "Bearer "

/**
 * Applies a client's text frame: a resize within bounds goes to the runner, one out of bounds is ignored.
 *
 * @return False when the frame is not one of the protocol's: the socket is then closed.
 */
internal suspend fun resized(relay: TerminalRelay, id: Uuid, text: String): Boolean {
    val control = try {
        TerminalJson.decodeFromString(TerminalControl.serializer(), text)
    } catch (_: IllegalArgumentException) {
        // SerializationException included: it extends IllegalArgumentException.
        return false
    }
    if (control is TerminalControl.Resize && control.columns in 1..MAX_SIZE && control.rows in 1..MAX_SIZE) {
        relay.resize(id, control.columns, control.rows)
    }
    return true
}

/** The close of a socket sent an unknown frame. */
internal val UNKNOWN_FRAME = CloseReason(TerminalCloseCodes.BAD_REQUEST, "Unknown terminal frame.")

/** Runs a write whose failure is left to a lease (a hold, renewed or given back). */
private suspend fun quietly(write: suspend () -> Unit) {
    try {
        write()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Left to the hold's lease.
    }
}
