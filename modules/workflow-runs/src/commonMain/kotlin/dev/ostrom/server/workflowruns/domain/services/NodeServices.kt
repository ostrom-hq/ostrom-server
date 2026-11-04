// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.services

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WaitSpec
import dev.ostrom.core.api.models.WorkflowRun
import kotlin.uuid.Uuid

/**
 * The question a wait node of a run asks.
 *
 * @property runId The run.
 * @property seq The visit: with the run, the key that makes a question asked twice (after a restart) find the first.
 * @property starter The login of the person who started the run: the question is asked for them.
 * @property assignee The login of the person asked; the starter when null.
 * @property question The question.
 */
data class WaitRequest(
    val runId: String,
    val seq: Int,
    val starter: String,
    val assignee: String?,
    val question: WaitSpec,
)

/**
 * What became of a question asked for.
 *
 * @property waitId The wait, when asked.
 * @property refusal Why it was not, otherwise.
 */
data class WaitOpening(val waitId: Uuid? = null, val refusal: String? = null)

/** Where the wait of a run is. */
enum class WaitState {
    /** Nobody answered yet. */
    OPEN,

    /** Answered: the first answer won. */
    ANSWERED,

    /** Withdrawn before an answer. */
    CANCELLED,
}

/**
 * The wait of a run as it is now.
 *
 * @property state Where it is.
 * @property action The action the answer chose, once answered: the visit's outcome.
 * @property values The answer's values, as a JSON object, once answered.
 */
data class WaitResult(val state: WaitState, val action: String = "", val values: String? = null)

/** The waits of the runs (the automations module's waits, asked of a member). */
interface RunWaits {
    /**
     * Asks the question of a visit, or finds the one already asked for it.
     *
     * @param orgId The organization.
     * @param request The question.
     *
     * @return The wait, or why none was asked.
     */
    suspend fun ask(orgId: Uuid, request: WaitRequest): WaitOpening

    /**
     * Reads where a wait is.
     *
     * @param orgId The organization.
     * @param waitId The wait.
     *
     * @return Where it is, or null when the organization has no such wait.
     */
    suspend fun result(orgId: Uuid, waitId: Uuid): WaitResult?

    /**
     * Withdraws the open wait of a cancelled run; one already answered or withdrawn is left as it is.
     *
     * @param orgId The organization.
     * @param starter The login of the person who started the run, for whom it was asked.
     * @param waitId The wait.
     */
    suspend fun cancel(orgId: Uuid, starter: String, waitId: Uuid)
}

/**
 * The child run a workflow node of a run starts.
 *
 * @property parent The run whose node starts it.
 * @property seq The visit: with the parent, the key that makes a child started twice (after a restart) the first one.
 * @property workflowId The child's workflow, of the same organization.
 * @property arguments The child's inputs, where `{{inputs.<id>}}` stands for the parent's input.
 */
data class ChildRequest(
    val parent: WorkflowRun,
    val seq: Int,
    val workflowId: String,
    val arguments: Map<String, String>,
)

/**
 * What became of a child run asked for.
 *
 * @property runId The child run, when started.
 * @property refusal Why it was not, otherwise.
 */
data class ChildOpening(val runId: String? = null, val refusal: String? = null)

/**
 * A child run as it is now.
 *
 * @property status Its status.
 * @property failure Why it failed.
 * @property output What it said.
 */
data class ChildResult(val status: RunStatus, val failure: String? = null, val output: String? = null)

/** The child runs of the runs: runs of another workflow of the organization, played by the same engine. */
interface RunChildren {
    /**
     * Starts the child run of a visit, or finds the one already started for it: of a workflow of the organization,
     * never one already running above the visit (no cycle), never deeper than the most runs a chain nests.
     *
     * @param orgId The organization.
     * @param request The child run.
     *
     * @return The child run, or why none was started.
     */
    suspend fun start(orgId: Uuid, request: ChildRequest): ChildOpening

    /**
     * Reads where a child run is.
     *
     * @param orgId The organization.
     * @param runId The child run.
     *
     * @return Where it is, or null when the organization has no such run.
     */
    suspend fun result(orgId: Uuid, runId: String): ChildResult?

    /**
     * Cancels the child run of a cancelled run; one already ended is left as it is.
     *
     * @param orgId The organization.
     * @param runId The child run.
     */
    suspend fun cancel(orgId: Uuid, runId: String)
}
