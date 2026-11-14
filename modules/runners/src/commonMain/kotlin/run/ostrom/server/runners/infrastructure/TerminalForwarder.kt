// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.TerminalHosts
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Opens a session's terminal on another replica, the one holding its runner's socket, for a member attached here. */
fun interface TerminalForwarder {
    /**
     * Opens the terminal as a hop client and runs [block] on its socket; returns once [block] does.
     *
     * @param replicaUrl The other replica's URL.
     * @param sessionId The session.
     * @param ticket A terminal ticket of the member, which the other replica redeems.
     * @param block What to do with the socket.
     */
    suspend fun forward(
        replicaUrl: String,
        sessionId: Uuid,
        ticket: String,
        block: suspend (DefaultWebSocketSession) -> Unit,
    )
}

/**
 * [TerminalForwarder] over a WebSocket client: `ws://<replica>/api/v1/cloud/sessions/{id}/terminal?ticket=&hop=1`, on
 * the replicas' own network (the chart's pod addresses, opened to the server pods only).
 *
 * @param client A client with WebSockets installed.
 */
class KtorTerminalForwarder(private val client: HttpClient) : TerminalForwarder {
    override suspend fun forward(
        replicaUrl: String,
        sessionId: Uuid,
        ticket: String,
        block: suspend (DefaultWebSocketSession) -> Unit,
    ) {
        val base = replicaUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        client.webSocket("$base/api/v1/cloud/sessions/$sessionId/terminal?ticket=$ticket&$HOP_PARAMETER=1") {
            block(this)
        }
    }

    /** The query parameter of a hop client. */
    companion object {
        /** Marks another replica relaying its member: attached here only, never relayed further. */
        const val HOP_PARAMETER = "hop"
    }
}

/**
 * Where this replica stands among the others for the terminals: its URL, the shared holds and the way to the others.
 *
 * @property url This replica's URL, as the others reach it.
 * @property hosts Which replica holds each runner's socket.
 * @property forwarder Opens a terminal on another replica.
 * @property renewEvery How often the holds of the runner sockets open here are renewed.
 * @property lookEvery How long a member waits before the runner is looked for again, and how often it is looked for
 *   elsewhere while no runner is here.
 */
class TerminalReplica(
    val url: String,
    val hosts: TerminalHosts,
    val forwarder: TerminalForwarder,
    val renewEvery: Duration = TerminalHosts.RENEWAL,
    val lookEvery: Duration = LOOK_EVERY,
) {
    /** Timing. */
    companion object {
        /** Between two looks for the runner: a member sees a moved terminal back within about this. */
        val LOOK_EVERY: Duration = 2.seconds
    }
}

/**
 * The WebSocket client of the hops to the other replicas.
 *
 * @return The client: Curl on native; CIO on the test-only JVM target.
 */
internal expect fun replicaHttpClient(): HttpClient
