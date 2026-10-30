// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [ServerApi] over HTTP: `POST /api/v1/cloud/runner/heartbeat` and `WS /api/v1/cloud/runner/terminal`, with the
 * runner token.
 *
 * @param client An HTTP client with WebSockets installed.
 * @param config The runner's configuration.
 * @param scope Where the terminal's incoming frames are read.
 */
class HttpServerApi(
    private val client: HttpClient,
    private val config: RunnerConfig,
    private val scope: CoroutineScope,
) : ServerApi {
    @Serializable
    private data class HeartbeatRequest(
        val sessionId: String,
        val epoch: Long,
        val ready: Boolean,
        val stopped: Boolean,
        val lastModelRequestAt: String?,
    )

    @Serializable
    private data class HeartbeatResponse(val instruction: String, val state: String)

    @Serializable
    private sealed interface Control {
        @Serializable
        @SerialName("resize")
        data class Resize(val columns: Int, val rows: Int) : Control
    }

    override suspend fun heartbeat(ready: Boolean, stopped: Boolean, lastModelRequestAt: String?): Instruction {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/heartbeat") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            val request = HeartbeatRequest(
                config.sessionId.toString(),
                config.epoch,
                ready,
                stopped,
                lastModelRequestAt,
            )
            setBody(JSON.encodeToString(HeartbeatRequest.serializer(), request))
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Unauthorized, HttpStatusCode.Conflict ->
                throw ExecutionEndedException("The server ended this execution (${response.status.value}).")
            else -> throw IllegalStateException("Heartbeat answered ${response.status.value}.")
        }
        val answer = JSON.decodeFromString(HeartbeatResponse.serializer(), response.bodyAsText())
        return if (answer.instruction == "stop") Instruction.STOP else Instruction.RUN
    }

    override suspend fun openTerminal(): TerminalLink {
        val query = "sessionId=${config.sessionId}&epoch=${config.epoch}"
        val url = config.serverUrl.replaceFirst("http", "ws") + "/api/v1/cloud/runner/terminal?$query"
        val session = client.webSocketSession(url) { bearerAuth(config.token) }
        return WebSocketLink(session, scope)
    }

    private class WebSocketLink(
        private val session: DefaultClientWebSocketSession,
        scope: CoroutineScope,
    ) : TerminalLink {
        override val incoming: ReceiveChannel<ServerFrame> = scope.produce {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Binary -> send(ServerFrame.Input(frame.readBytes()))
                    is Frame.Text -> parse(frame.readText())?.let { send(it) }
                    else -> Unit
                }
            }
            val reason = session.closeReason.await()
            if (reason != null && reason.code in ENDED_CODES) {
                throw ExecutionEndedException("The server closed the terminal (${reason.code}).")
            }
        }

        override suspend fun send(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

        override suspend fun close() = session.close()
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        /** 4401 (token refused) and 4409 (another execution): this runner is over. */
        private const val TOKEN_REFUSED: Short = 4401
        private const val ANOTHER_EXECUTION: Short = 4409
        private val ENDED_CODES = setOf(TOKEN_REFUSED, ANOTHER_EXECUTION)

        /**
         * Reads a text frame from the server.
         *
         * @param text The frame.
         *
         * @return The frame, or null when it is not one the runner acts on.
         */
        fun parse(text: String): ServerFrame? = try {
            when (val control = JSON.decodeFromString(Control.serializer(), text)) {
                is Control.Resize -> ServerFrame.Resize(control.columns, control.rows)
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
