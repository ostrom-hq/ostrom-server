package dev.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/cloud/sessions` (the caller's sessions, `?before=<id>&limit=`) and `POST` (create), as in
 * ostrom-core's `api/openapi.yaml` (operations `listCloudSessions`, `createCloudSession`).
 *
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 50.
 */
@Serializable
@Resource("/api/v1/cloud/sessions")
class CloudSessionsApi(val before: String? = null, val limit: Int? = null) {
    /**
     * `GET`/`DELETE /api/v1/cloud/sessions/{id}`.
     *
     * @property parent The collection.
     * @property id The session id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: CloudSessionsApi = CloudSessionsApi(), val id: String) {
        /**
         * `POST /api/v1/cloud/sessions/{id}/pause`.
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("pause")
        class Pause(val parent: Id)

        /**
         * `POST /api/v1/cloud/sessions/{id}/resume`.
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("resume")
        class Resume(val parent: Id)

        /**
         * `/v1/cloud/sessions/{id}/terminal` (the WebSocket itself is a plain route).
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("terminal")
        class Terminal(val parent: Id) {
            /**
             * `POST /v1/cloud/sessions/{id}/terminal/ticket`.
             *
             * @property parent The terminal.
             */
            @Serializable
            @Resource("ticket")
            class Ticket(val parent: Terminal)
        }
    }
}

/** `POST /api/v1/cloud/runner/heartbeat`: the runner channel, authenticated by the execution's runner token. */
@Serializable
@Resource("/api/v1/cloud/runner/heartbeat")
class RunnerHeartbeatApi

/**
 * Creates a session.
 *
 * @property agent `claude`, `codex`, `opencode`, `gemini` or `shell`.
 * @property name A label, up to 120 characters; the repository's or the agent's name when absent.
 * @property repo The GitHub repository to clone at the first start, `https://github.com/<owner>/<name>`.
 * @property ref The branch, tag or commit to check out; the default branch when absent.
 * @property prompt The agent's first message, up to 20,000 characters; an interactive agent when absent.
 * @property model The model the agent starts with; the agent's default when absent.
 */
@Serializable
data class CreateCloudSessionRequest(
    val agent: String,
    val name: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val prompt: String? = null,
    val model: String? = null,
)

/**
 * Why a session failed or stopped.
 *
 * @property code A stable key: `start_failed`, `execution_lost`, `idle_timeout`, `max_duration`, `hours_exhausted`.
 * @property message A sentence for humans.
 */
@Serializable
data class SessionFailureDto(
    val code: String,
    val message: String,
)

/**
 * The bounds a session runs under.
 *
 * @property idleTimeoutMinutes Minutes without a request to the model nor terminal input before it pauses.
 * @property maxRunMinutes Minutes an execution may run before it pauses.
 * @property restrictedEgress Whether its outbound network is limited.
 */
@Serializable
data class SessionLimitsDto(
    val idleTimeoutMinutes: Int,
    val maxRunMinutes: Int,
    val restrictedEgress: Boolean,
)

/**
 * A cloud session as clients see it (schema `CloudSession` of the contract). `status` is a closed enum
 * (`creating`, `running`, `pausing`, `paused`, `resuming`, `terminated`, `failed`), `desiredStatus` what the
 * owner asked for (`running`, `paused`, `terminated`); `phase` is open.
 */
@Serializable
data class CloudSessionResponse(
    val id: String,
    val name: String,
    val owner: String,
    val agent: String,
    val model: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val status: String,
    val desiredStatus: String,
    val phase: String? = null,
    val epoch: Long,
    val failure: SessionFailureDto? = null,
    val limits: SessionLimitsDto,
    val createdAt: String,
    val updatedAt: String,
    val readyAt: String? = null,
    val lastActivityAt: String? = null,
    val pausedAt: String? = null,
    val endedAt: String? = null,
)

/**
 * A page of sessions.
 *
 * @property items The sessions, newest first.
 * @property next The cursor of the next page (`?before=`), absent on the last one.
 */
@Serializable
data class CloudSessionsResponse(
    val items: List<CloudSessionResponse>,
    val next: String? = null,
)

/**
 * A runner's heartbeat.
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 * @property ready Whether the agent is started.
 * @property stopped Whether the agent stopped after a `stop` instruction.
 * @property lastModelRequestAt The agent's last request to the model (ISO 8601), if seen.
 */
@Serializable
data class RunnerHeartbeatRequest(
    val sessionId: String,
    val epoch: Long,
    val ready: Boolean = false,
    val stopped: Boolean = false,
    val lastModelRequestAt: String? = null,
)

/**
 * The answer to a heartbeat.
 *
 * @property instruction `run` or `stop`.
 * @property state The session's state.
 */
@Serializable
data class RunnerHeartbeatResponse(
    val instruction: String,
    val state: String,
)
