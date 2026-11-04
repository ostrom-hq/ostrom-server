// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.models

import dev.ostrom.core.api.models.NodeVisit
import dev.ostrom.core.api.models.WorkflowRun
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A run the engine leased: which organization it belongs to, and its id.
 *
 * @property orgId The organization.
 * @property id The run.
 */
data class RunRef(val orgId: Uuid, val id: String)

/**
 * A run as the engine plays it.
 *
 * @property run The run as stored.
 * @property graph The graph it copied at its start; empty for a run started before the server played runs, which
 *   then fails with nothing to play.
 */
data class PlayedRun(val run: WorkflowRun, val graph: RunGraph)

/**
 * A line of the organization's signed audit log about a run, written in the transaction that changes the run.
 *
 * @property event What happened, after `workflow_run.` (`started`, `node_started`, `gate_opened`, `wait_asked`,
 *   `child_started`, `ended`, `cancelled`).
 * @property details Extra facts (node, kind, session, gate, wait, child run, status), never an input's value nor a
 *   prompt.
 * @property actor The person who did it, or null for the server's engine.
 */
data class RunAudit(val event: String, val details: Map<String, String> = emptyMap(), val actor: Uuid? = null)

/**
 * One change of a run, written in one transaction only if the run is still as it was read ([expected]): the stored
 * run is locked (`FOR UPDATE`) and compared first, so a cancel or another replica's step is never overwritten.
 *
 * @property expected The run as it was read.
 * @property next The run as it becomes.
 * @property owner The engine replica whose lease must still hold, or null for a person's change (a cancel).
 * @property visit The visit this change begins: its key (organization, run, `seq`) is inserted once, so a visit is
 *   never begun twice, whatever restarts.
 * @property audit The audit lines of the change.
 * @property dueAt When the engine looks at the run again, for a person's change; the engine's own lease decides
 *   otherwise.
 */
data class RunChange(
    val expected: WorkflowRun,
    val next: WorkflowRun,
    val owner: String?,
    val visit: NodeVisit? = null,
    val audit: List<RunAudit> = emptyList(),
    val dueAt: Instant? = null,
)
