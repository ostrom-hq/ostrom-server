// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/**
 * Where executions run: a Kubernetes namespace (production) or the local Docker engine (tests, self-hosting).
 * Every call is idempotent, so the reconciler can repeat any step after a crash or a lost race.
 */
interface RuntimeDriver {
    /**
     * Whether a lost execution starts again elsewhere rather than pausing its session: true where executions run on
     * machines that may die (registered runners), whose workspace is lost with them.
     */
    val relocates: Boolean get() = false

    /**
     * Creates the session's workspace volume unless it exists.
     *
     * @param session The session.
     */
    suspend fun ensureWorkspace(session: CloudSession)

    /**
     * Starts an execution; starting one that exists changes nothing.
     *
     * @param spec What to run.
     */
    suspend fun startExecution(spec: ExecutionSpec)

    /**
     * Reads the state of the session's current execution (its [CloudSession.epoch]).
     *
     * @param session The session.
     *
     * @return The state.
     */
    suspend fun executionStatus(session: CloudSession): ExecutionStatus

    /**
     * Stops and removes every execution of the session, whatever its epoch, and their credentials.
     *
     * @param session The session.
     */
    suspend fun stopExecutions(session: CloudSession)

    /**
     * Deletes the session's workspace volume.
     *
     * @param session The session.
     *
     * @return True once the volume is proven gone, false while its deletion is still in progress.
     */
    suspend fun deleteWorkspace(session: CloudSession): Boolean
}

/**
 * One execution to start.
 *
 * @property sessionId The session.
 * @property orgId The session's organization.
 * @property epoch The execution's number.
 * @property agent The agent the runner starts.
 * @property repository The repository the runner clones at the first start.
 * @property runnerToken The execution's runner token, handed to the runner only (a Kubernetes Secret, a Docker
 *   environment variable); never stored in clear by the server.
 * @property limits The session's bounds (egress restriction).
 * @property model The model the agent starts with, if chosen.
 * @property prompt The agent's first message, given at the first execution only.
 */
data class ExecutionSpec(
    val sessionId: Uuid,
    val orgId: Uuid,
    val epoch: Long,
    val agent: AgentKind,
    val repository: RepositorySpec?,
    val runnerToken: String,
    val limits: SessionLimits,
    val model: String? = null,
    val prompt: String? = null,
) {
    override fun toString(): String =
        "ExecutionSpec(sessionId=$sessionId, epoch=$epoch, agent=${agent.wire}, model=$model, runnerToken=***, " +
            "prompt=${if (prompt == null) "none" else "***"})"
}

/**
 * The execution of a session's current epoch.
 *
 * @param runnerToken The execution's runner token.
 *
 * @return What to start; the first message goes to the first execution only.
 */
fun CloudSession.executionSpec(runnerToken: String): ExecutionSpec =
    ExecutionSpec(id, orgId, epoch, agent, repository, runnerToken, limits, model, prompt.takeIf { epoch == 1L })

/** The state of an execution as the runtime sees it. */
enum class ExecutionStatus {
    /**
     * Waiting for a machine with room to take it (registered runners): it waits as long as it takes, and its start is
     * timed from the moment a machine takes it.
     */
    QUEUED,

    /** Accepted, not started yet (image pull, scheduling). */
    PENDING,

    /** Its runner process runs. */
    RUNNING,

    /** It ended (the runner exited), whatever the exit code. */
    ENDED,

    /** The runtime cannot start it (bad image, unschedulable for good). */
    FAILED,

    /** The runtime has no such execution. */
    MISSING,

    /** The machine that runs it stopped answering: it can start again on another one. */
    HOST_LOST,
}

/** The names a driver gives to a session's objects, the same in every runtime. */
object RuntimeNames {
    /**
     * The workspace volume of a session.
     *
     * @param sessionId The session.
     *
     * @return `ostrom-ws-<32 hex>`.
     */
    fun workspace(sessionId: Uuid): String = "ostrom-ws-" + sessionId.toHexString()

    /**
     * The pod or container of an execution: deterministic, so creating it twice is harmless.
     *
     * @param sessionId The session.
     * @param epoch The execution.
     *
     * @return `ostrom-s-<32 hex>-e<epoch>`.
     */
    fun execution(sessionId: Uuid, epoch: Long): String = "ostrom-s-" + sessionId.toHexString() + "-e" + epoch
}
