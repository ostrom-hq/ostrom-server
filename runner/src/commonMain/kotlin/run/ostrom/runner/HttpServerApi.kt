// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

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
import io.ktor.http.isSuccess
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [ServerApi] over HTTP, with the runner token: `POST /api/v1/cloud/runner/heartbeat`, `WS
 * /api/v1/cloud/runner/terminal`, `POST /api/v1/cloud/runner/launch` and `POST /api/v1/cloud/runner/checks`.
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
    private data class ExecutionRequest(val sessionId: String, val epoch: Long)

    @Serializable
    private data class LaunchResponse(val launchId: String, val agentToken: String)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val message: String)

    @Serializable
    private data class CheckRequest(val sessionId: String, val epoch: Long, val check: RunnerCheck)

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
            else -> error("Heartbeat answered ${response.status.value}.")
        }
        val answer = JSON.decodeFromString(HeartbeatResponse.serializer(), response.bodyAsText())
        return if (answer.instruction == "stop") Instruction.STOP else Instruction.RUN
    }

    override suspend fun openLaunch(): RunnerLaunch {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/launch") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            val request = ExecutionRequest(config.sessionId.toString(), config.epoch)
            setBody(JSON.encodeToString(ExecutionRequest.serializer(), request))
        }
        val status = response.status
        return when {
            status.isSuccess() -> JSON.decodeFromString(LaunchResponse.serializer(), response.bodyAsText())
                .let { RunnerLaunch.Opened(it.launchId, it.agentToken) }
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Conflict ->
                throw ExecutionEndedException("The server ended this execution (${status.value}).")
            status.value < SERVER_ERRORS -> RunnerLaunch.Refused(reason(response.bodyAsText(), status))
            else -> error("The launch answered ${status.value}.")
        }
    }

    override suspend fun reportCheck(check: RunnerCheck): Boolean {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/checks") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            val request = CheckRequest(config.sessionId.toString(), config.epoch, check)
            setBody(JSON.encodeToString(CheckRequest.serializer(), request))
        }
        return response.status.isSuccess()
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
            for (frame in session.incoming) read(frame)?.let { send(it) }
            // The end closes the channel with its cause instead of being thrown: thrown, it would also fail the
            // scope it runs in, the runner's own, and kill the process instead of letting it exit with its code.
            channel.close(ended(session.closeReason.await()))
        }

        override suspend fun send(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

        override suspend fun close() = session.close()
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        /** 4401 (token refused) and 4409 (another execution): this runner is over. */
        private const val TOKEN_REFUSED: Short = 4401
        private const val ANOTHER_EXECUTION: Short = 4409
        private val ENDED_CODES = setOf(TOKEN_REFUSED, ANOTHER_EXECUTION)
        private const val SERVER_ERRORS = 500

        /**
         * Why the server refused: the message of its error body, or the status when the body is not one.
         *
         * @param body The answer's body.
         * @param status The answer's status.
         *
         * @return The reason.
         */
        private fun reason(body: String, status: HttpStatusCode): String = try {
            JSON.decodeFromString(ErrorBody.serializer(), body).error.message
        } catch (_: IllegalArgumentException) {
            "The server answered ${status.value}."
        }

        /**
         * Reads a frame from the server: binary frames are keystrokes, text frames controls.
         *
         * @param frame The frame.
         *
         * @return The frame, or null when it is not one the runner acts on.
         */
        fun read(frame: Frame): ServerFrame? = when (frame) {
            is Frame.Binary -> ServerFrame.Input(frame.readBytes())
            is Frame.Text -> parse(frame.readText())
            else -> null
        }

        /**
         * Whether the server closed the terminal because this execution is over.
         *
         * @param reason The close reason, null when the connection dropped without one.
         *
         * @return The end, for a refused token or another execution; null when the link may be opened again.
         */
        fun ended(reason: CloseReason?): ExecutionEndedException? = reason
            ?.takeIf { it.code in ENDED_CODES }
            ?.let { ExecutionEndedException("The server closed the terminal (${it.code}).") }

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
        } catch (_: IllegalArgumentException) {
            // SerializationException included: it extends IllegalArgumentException.
            null
        }
    }
}
