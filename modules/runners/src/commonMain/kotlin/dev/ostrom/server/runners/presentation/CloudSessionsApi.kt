package dev.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /v1/cloud/sessions` (the caller's sessions, `?before=<id>&limit=`) and `POST` (create). To move to
 * ostrom-core with the API contract.
 *
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 50.
 */
@Serializable
@Resource("/v1/cloud/sessions")
class CloudSessionsApi(val before: String? = null, val limit: Int? = null) {
    /**
     * `GET`/`DELETE /v1/cloud/sessions/{id}`.
     *
     * @property parent The collection.
     * @property id The session id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: CloudSessionsApi = CloudSessionsApi(), val id: String) {
        /**
         * `POST /v1/cloud/sessions/{id}/pause`.
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("pause")
        class Pause(val parent: Id)

        /**
         * `POST /v1/cloud/sessions/{id}/resume`.
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("resume")
        class Resume(val parent: Id)
    }
}

/** `POST /v1/runner/heartbeat`: the runner channel, authenticated by the execution's runner token. */
@Serializable
@Resource("/v1/runner/heartbeat")
class RunnerHeartbeatApi

/**
 * Creates a session.
 *
 * @property name A label, 1 to 120 characters.
 * @property agent `claude`, `codex`, `opencode`, `gemini` or `shell`.
 * @property repository The GitHub repository to clone at the first start.
 */
@Serializable
data class CreateCloudSessionRequest(
    val name: String,
    val agent: String,
    val repository: RepositoryDto? = null,
)

/**
 * A repository.
 *
 * @property url `https://github.com/<owner>/<name>`.
 * @property ref A branch, tag or commit; the default branch when absent.
 */
@Serializable
data class RepositoryDto(
    val url: String,
    val ref: String? = null,
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
 * A cloud session as clients see it. `state` is a closed enum (`creating`, `running`, `pausing`, `paused`,
 * `resuming`, `terminated`, `failed`); `phase` is open.
 */
@Serializable
data class CloudSessionResponse(
    val id: String,
    val name: String,
    val ownerId: String,
    val agent: String,
    val repository: RepositoryDto? = null,
    val state: String,
    val desiredState: String,
    val phase: String? = null,
    val epoch: Long,
    val failure: SessionFailureDto? = null,
    val limits: SessionLimitsDto,
    val createdAt: String,
    val updatedAt: String,
    val readyAt: String? = null,
    val lastActivityAt: String? = null,
    val pausedAt: String? = null,
    val terminatedAt: String? = null,
)

/**
 * A page of sessions.
 *
 * @property sessions The sessions, newest first.
 * @property nextBefore The cursor of the next page, absent on the last one.
 */
@Serializable
data class CloudSessionsResponse(
    val sessions: List<CloudSessionResponse>,
    val nextBefore: String? = null,
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
