// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

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
         * `PUT /api/v1/cloud/sessions/{id}/github` (`setCloudSessionGithubAccess`).
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("github")
        class GitHub(val parent: Id)

        /**
         * `/api/v1/cloud/sessions/{id}/terminal` (the WebSocket itself is a plain route).
         *
         * @property parent The session.
         */
        @Serializable
        @Resource("terminal")
        class Terminal(val parent: Id) {
            /**
             * `POST /api/v1/cloud/sessions/{id}/terminal/ticket`.
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

/** `POST /api/v1/cloud/runner/github` (`fetchRunnerGithubToken`): the owner's GitHub token, for the runner. */
@Serializable
@Resource("/api/v1/cloud/runner/github")
class RunnerGitHubApi

/** `POST /api/v1/cloud/runner/variables` (`fetchRunnerVariables`): the owner's environment variables. */
@Serializable
@Resource("/api/v1/cloud/runner/variables")
class RunnerVariablesApi

/** `POST /api/v1/cloud/runner/environment` (`fetchRunnerEnvironment`): what the runner prepares. */
@Serializable
@Resource("/api/v1/cloud/runner/environment")
class RunnerEnvironmentApi

/** `POST /api/v1/cloud/runner/preparation` (`reportRunnerPreparation`): a phase, or its failure. */
@Serializable
@Resource("/api/v1/cloud/runner/preparation")
class RunnerPreparationApi

/**
 * The contract's `CloudRepository`.
 *
 * @property url `https://github.com/<owner>/<name>[.git]`.
 * @property path Relative to `/workspace`; the repository's name when absent.
 * @property ref The branch, tag or commit checked out; the default branch when absent.
 * @property branch A local branch created at `ref`.
 */
@Serializable
data class CloudRepositoryDto(
    val url: String,
    val path: String? = null,
    val ref: String? = null,
    val branch: String? = null,
)

/**
 * The contract's `CloudSetup`.
 *
 * @property create Run once per workspace.
 * @property start Run on every execution.
 */
@Serializable
data class CloudSetupDto(val create: List<String> = emptyList(), val start: List<String> = emptyList())

/**
 * The contract's `CloudEnvironment`: a session's preparation recipe.
 *
 * @property repositories Cloned on the first execution, fetched afterwards.
 * @property workingDirectory Relative to `/workspace`.
 * @property setup The setup commands.
 * @property requiredVariables The variables the owner must be assigned.
 * @property requireGitHub Whether a usable GitHub link is required.
 * @property gitEmailDomain The Git e-mail domain of an owner without a GitHub link.
 */
@Serializable
data class CloudEnvironmentDto(
    val repositories: List<CloudRepositoryDto> = emptyList(),
    val workingDirectory: String? = null,
    val setup: CloudSetupDto = CloudSetupDto(),
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean = false,
    val gitEmailDomain: String? = null,
)

/**
 * The contract's `RunnerGitIdentity`.
 *
 * @property name The `user.name`.
 * @property email The `user.email`.
 */
@Serializable
data class RunnerGitIdentityDto(val name: String, val email: String)

/**
 * The contract's `RunnerEnvironment`, never logged.
 *
 * @property environment The recipe the runner prepares.
 * @property git The owner's Git identity.
 * @property variables The owner's environment variables, by name.
 * @property unavailable The variables withheld, by name.
 */
@Serializable
data class RunnerEnvironmentResponse(
    val environment: CloudEnvironmentDto,
    val git: RunnerGitIdentityDto,
    val variables: Map<String, String>,
    val unavailable: List<String>,
) {
    override fun toString(): String =
        "RunnerEnvironmentResponse(${environment.repositories.size} repositories, ${variables.keys.sorted()}, " +
            "unavailable=$unavailable)"
}

/**
 * The contract's `RunnerPreparationFailure`.
 *
 * @property step The failed step.
 * @property index The repository or the setup command, from 0.
 * @property exitCode The command's exit code.
 */
@Serializable
data class RunnerPreparationFailureDto(val step: String, val index: Int? = null, val exitCode: Int? = null)

/**
 * The contract's `RunnerPreparationReport`.
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 * @property phase The phase starting, or the one that failed.
 * @property failure The failed step.
 */
@Serializable
data class RunnerPreparationRequest(
    val sessionId: String,
    val epoch: Long,
    val phase: String,
    val failure: RunnerPreparationFailureDto? = null,
)

/**
 * The contract's `RunnerExecution`: the execution a runner call is for.
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 */
@Serializable
data class RunnerExecutionBody(val sessionId: String, val epoch: Long)

/**
 * The contract's `EnvironmentValues`, never logged.
 *
 * @property variables The values by name.
 * @property unavailable The variables withheld, by name.
 */
@Serializable
data class RunnerVariablesResponse(val variables: Map<String, String>, val unavailable: List<String>) {
    override fun toString(): String = "RunnerVariablesResponse(${variables.keys.sorted()}, unavailable=$unavailable)"
}

/**
 * The body of `PUT /api/v1/cloud/sessions/{id}/github`.
 *
 * @property access `owner`, `shared` or `none`.
 */
@Serializable
data class SetCloudSessionGitHubAccessRequest(val access: String)

/**
 * The body of `POST /api/v1/cloud/runner/github`.
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 */
@Serializable
data class RunnerGitHubTokenRequest(val sessionId: String, val epoch: Long)

/**
 * The contract's `RunnerGitHubToken`.
 *
 * @property status `linked`, `not_linked` or `unavailable`.
 * @property token The token, when linked.
 * @property expiresAt When it expires.
 * @property login The GitHub account.
 */
@Serializable
data class RunnerGitHubTokenResponse(
    val status: String,
    val token: String? = null,
    val expiresAt: String? = null,
    val login: String? = null,
)

/**
 * Creates a session.
 *
 * @property agent `claude`, `codex`, `opencode`, `gemini` or `shell`.
 * @property name A label, up to 120 characters; the repository's or the agent's name when absent.
 * @property repo The GitHub repository to clone at the first start, `https://github.com/<owner>/<name>`.
 * @property ref The branch, tag or commit to check out; the default branch when absent.
 * @property prompt The agent's first message, up to 20,000 characters; an interactive agent when absent.
 * @property model The model the agent starts with; the agent's default when absent.
 * @property environment The preparation recipe (`runners.cloud.environment.v1`).
 * @property profileId The cloud profile a Kubernetes session runs on (`runners.cloud.profiles.v1`); the one the owner
 *   may use when absent.
 * @property image The session image instead of the profile's: one of its allowed overrides.
 */
@Serializable
data class CreateCloudSessionRequest(
    val agent: String,
    val name: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    val environment: CloudEnvironmentDto? = null,
    val profileId: String? = null,
    val image: String? = null,
)

/**
 * The cloud profile a session runs on, at the version it was created with (`CloudSessionProfile`).
 *
 * @property id The profile.
 * @property version Its version.
 */
@Serializable
data class CloudSessionProfileDto(val id: String, val version: Int)

/**
 * Why a session failed or stopped.
 *
 * @property code A stable key: `start_failed`, `execution_lost`, `idle_timeout`, `max_duration`, `hours_exhausted`,
 *   `preparation_failed`, `cluster_unavailable`, `namespace_not_allowed`.
 * @property message A sentence for humans.
 * @property retryable Whether trying again may succeed, for `preparation_failed`.
 */
@Serializable
data class SessionFailureDto(
    val code: String,
    val message: String,
    val retryable: Boolean? = null,
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
 * owner asked for (`running`, `paused`, `terminated`); `phase` is open. `runnerId` names the registered runner the
 * current execution was handed to, on the session's own read only (`GET /api/v1/cloud/sessions/{id}`).
 * `githubAccess` says whose GitHub access its Git and `gh` use (`owner`, `shared`, `none`). `profile` and `image`
 * say what a Kubernetes session runs on (`runners.cloud.profiles.v1`).
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
    val runnerId: String? = null,
    val githubAccess: String = "owner",
    val environment: CloudEnvironmentDto? = null,
    val profile: CloudSessionProfileDto? = null,
    val image: String? = null,
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
 * @property background Whether the agent has background work; an older runner never says it.
 */
@Serializable
data class RunnerHeartbeatRequest(
    val sessionId: String,
    val epoch: Long,
    val ready: Boolean = false,
    val stopped: Boolean = false,
    val lastModelRequestAt: String? = null,
    val background: Boolean = false,
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
