// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.models

import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowStatus

/**
 * A workflow as a run starts it: what the run copies (its name, version and graph) and what it checks (its status,
 * the inputs it declares).
 *
 * @property id The workflow.
 * @property name Its name.
 * @property version Its saved version, which the run records.
 * @property status Proposed, accepted or edited.
 * @property inputs The inputs it declares.
 * @property graph Its nodes and edges, which the run copies and plays.
 */
data class RunWorkflow(
    val id: String,
    val name: String,
    val version: Int,
    val status: WorkflowStatus,
    val inputs: List<WorkflowInput>,
    val graph: RunGraph = RunGraph(),
)
