// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.services

import kotlin.uuid.Uuid

/**
 * The cloud session an agent node of a run asks for.
 *
 * @property sessionId The session's id, chosen by the run before the session exists: the key that makes a start
 *   asked twice (after a restart) find the first one.
 * @property starter The login of the person who started the run: the session runs as them.
 * @property name The session's label.
 * @property agent The agent's wire name (`claude`, `codex`…).
 * @property model The model, if the node names one.
 * @property prompt The agent's first message.
 */
data class AgentRequest(
    val sessionId: Uuid,
    val starter: String,
    val name: String,
    val agent: String,
    val model: String?,
    val prompt: String,
)

/** Where an agent node's session is. */
enum class AgentState {
    /** It holds, or is about to hold, an execution: the agent works. */
    WORKING,

    /** It no longer holds one: paused or ended. */
    DONE,

    /** Its start failed. */
    FAILED,
}

/** The agent sessions of the runs (the runners module's cloud sessions, started through the launch path). */
interface RunAgents {
    /**
     * Starts a cloud session, within the organization's plan, owned by the run's starter.
     *
     * @param orgId The organization.
     * @param request The session.
     *
     * @return Null once started, or why it was refused (a stable reason the visit shows).
     */
    suspend fun start(orgId: Uuid, request: AgentRequest): String?

    /**
     * Reads where a session is.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     *
     * @return Its state, or null when it does not exist (not started yet).
     */
    suspend fun state(orgId: Uuid, sessionId: Uuid): AgentState?

    /**
     * Ends a session of a cancelled run; one already ended is left as it is.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     */
    suspend fun stop(orgId: Uuid, sessionId: Uuid)
}

/**
 * The gate a gate node of a run opens.
 *
 * @property runId The run.
 * @property seq The visit: with the run, the key that makes a gate asked twice join the first one.
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The node's label.
 * @property approvers The logins who may say yes.
 * @property asker The login of the person who started the run: the yes is asked for them.
 */
data class GateRequest(
    val runId: String,
    val seq: Int,
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val approvers: List<String>,
    val asker: String,
)

/** Where a gate of a run is. */
enum class GateState {
    /** Waiting for an answer. */
    PENDING,

    /** An approver said yes. */
    APPROVED,

    /** An approver said no. */
    REJECTED,
}

/**
 * What became of a gate asked for.
 *
 * @property gateId The gate, when opened.
 * @property refusal Why it was not, otherwise.
 */
data class GateOpening(val gateId: Uuid? = null, val refusal: String? = null)

/** The gates of the runs (the gates module). */
interface RunGates {
    /**
     * Opens the gate of a visit, enforced, or joins the one already opened for it.
     *
     * @param orgId The organization.
     * @param request The gate.
     *
     * @return The gate, or why none was opened.
     */
    suspend fun open(orgId: Uuid, request: GateRequest): GateOpening

    /**
     * Reads where a gate is.
     *
     * @param orgId The organization.
     * @param gateId The gate.
     *
     * @return Its state, or null when the organization has no such gate.
     */
    suspend fun state(orgId: Uuid, gateId: Uuid): GateState?
}
