// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A cloud session: an agent that runs in a pod (or a local Docker container) the server starts, with a workspace
 * that survives pauses. The session is the durable identity; each start of a pod is an execution, numbered by
 * [epoch], so that a runner of an older execution can never act on the session again.
 *
 * API routes only write [desiredState]; the reconciler moves [state] towards it.
 *
 * @property id The session id.
 * @property orgId The organization the session belongs to; every query filters on it.
 * @property ownerId The member the session runs for.
 * @property name A label chosen by the owner.
 * @property agent The agent the runner starts in the terminal.
 * @property repository The repository cloned into the workspace at the first start, if any.
 * @property state Where the session is.
 * @property desiredState Where the owner wants it.
 * @property phase A detail of [state] for humans (`starting_runner`, `idle_timeout`…); clients tolerate unknown
 *   values.
 * @property epoch The number of the current (or last) execution; 0 before the first one.
 * @property generation Incremented by every write, for compare-and-set.
 * @property failure Why the session failed, or why its last start or run stopped.
 * @property limits The bounds the session runs under, copied from the plan at creation.
 * @property createdAt When the session was created.
 * @property updatedAt When the session last changed.
 * @property executionStartedAt When the current execution was started, null while none runs.
 * @property readyAt When the runner of the current execution reported ready.
 * @property lastHeartbeatAt The last heartbeat of the current execution's runner.
 * @property lastActivityAt The last request to the model, terminal input, or the moment the session became ready.
 * @property pausedAt When the session was last paused.
 * @property terminatedAt When the session was terminated.
 * @property runnerTokenHash The SHA-256 (hex) of the current execution's runner token; null while none runs.
 * @property runnerStoppedAt When the runner of the current execution reported its agent stopped (pause drained).
 * @property pausingSince When the session entered `pausing`, for the pause deadline.
 * @property model The model the agent starts with, if chosen.
 * @property prompt The first message the agent receives at the first start, if any.
 * @property runnerId The registered runner its current execution was handed to: named on a read of the session
 *   ([GetCloudSessionUseCase]), never stored with it; null on the cluster's or this server's Docker.
 */
data class CloudSession(
    val id: Uuid,
    val orgId: Uuid,
    val ownerId: String,
    val name: String,
    val agent: AgentKind,
    val repository: RepositorySpec?,
    val state: SessionState,
    val desiredState: DesiredState,
    val phase: String?,
    val epoch: Long,
    val generation: Long,
    val failure: SessionFailure?,
    val limits: SessionLimits,
    val createdAt: Instant,
    val updatedAt: Instant,
    val executionStartedAt: Instant? = null,
    val readyAt: Instant? = null,
    val lastHeartbeatAt: Instant? = null,
    val lastActivityAt: Instant? = null,
    val pausedAt: Instant? = null,
    val terminatedAt: Instant? = null,
    val runnerTokenHash: String? = null,
    val runnerStoppedAt: Instant? = null,
    val pausingSince: Instant? = null,
    val model: String? = null,
    val prompt: String? = null,
    val runnerId: Uuid? = null,
)

/**
 * The states of a session. The reconciler alone moves a session between them, checked by [SessionStateMachine].
 *
 * @property wire The stable name in the API and the database.
 */
enum class SessionState(val wire: String) {
    /** Accepted; the workspace and the first execution are being started. */
    CREATING("creating"),

    /** The runner reported ready: the agent runs and terminals can attach. */
    RUNNING("running"),

    /** The runner is stopping the agent before its execution ends. */
    PAUSING("pausing"),

    /** No execution; the workspace is kept. */
    PAUSED("paused"),

    /** A new execution is starting on the kept workspace. */
    RESUMING("resuming"),

    /** Deleted: no execution, workspace deleted. Final. */
    TERMINATED("terminated"),

    /** Unusable: the first start failed. The workspace is deleted. Final. */
    FAILED("failed"),
    ;

    /** Whether the session holds or is about to hold an execution, and so counts towards the running cap. */
    val holdsExecution: Boolean get() = this == CREATING || this == RUNNING || this == PAUSING || this == RESUMING

    /** Whether the session is over. */
    val isFinal: Boolean get() = this == TERMINATED || this == FAILED

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a state from its wire name.
         *
         * @param wire The stable name.
         *
         * @return The state.
         *
         * @throws IllegalArgumentException When the name is unknown.
         */
        fun fromWire(wire: String): SessionState =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("Unknown session state $wire.")
    }
}

/**
 * Where the owner wants the session.
 *
 * @property wire The stable name in the API and the database.
 */
enum class DesiredState(val wire: String) {
    RUNNING("running"),
    PAUSED("paused"),
    TERMINATED("terminated"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a desired state from its wire name.
         *
         * @param wire The stable name.
         *
         * @return The desired state.
         *
         * @throws IllegalArgumentException When the name is unknown.
         */
        fun fromWire(wire: String): DesiredState =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("Unknown desired state $wire.")
    }
}

/**
 * The agents a cloud session can run. The runner image carries each of them; the runner starts [command] in the
 * session's terminal.
 *
 * @property wire The stable name in the API and the database.
 * @property command The program the runner starts.
 */
enum class AgentKind(val wire: String, val command: String) {
    CLAUDE("claude", "claude"),
    CODEX("codex", "codex"),
    OPENCODE("opencode", "opencode"),
    GEMINI("gemini", "gemini"),

    /** A plain shell, to prepare a workspace or debug an image. */
    SHELL("shell", "bash"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads an agent from its wire name.
         *
         * @param wire The stable name.
         *
         * @return The agent, or null when the name is unknown.
         */
        fun fromWire(wire: String): AgentKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A repository the runner clones into the workspace at the first start.
 *
 * @property url The HTTPS URL of a GitHub repository.
 * @property ref The branch, tag or commit to check out; the default branch when null.
 */
data class RepositorySpec(
    val url: String,
    val ref: String?,
)

/**
 * Why a session failed or stopped.
 *
 * @property code A stable snake_case key (`start_failed`, `execution_lost`, `idle_timeout`, `max_duration`,
 *   `hours_exhausted`).
 * @property message A sentence for humans, never a raw error of the cluster.
 */
data class SessionFailure(
    val code: String,
    val message: String,
)

/**
 * The bounds a session runs under, copied from the organization's plan when it is created so that a plan change
 * never alters a running session by surprise.
 *
 * @property idleTimeoutMinutes Minutes without a request to the model nor terminal input before the session pauses.
 * @property maxRunMinutes Minutes one execution may run before it pauses.
 * @property restrictedEgress Whether the pod's outbound traffic is limited (free plan).
 */
data class SessionLimits(
    val idleTimeoutMinutes: Int,
    val maxRunMinutes: Int,
    val restrictedEgress: Boolean,
)
