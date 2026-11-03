// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.models

import dev.ostrom.core.api.models.WorkflowInput
import dev.ostrom.core.api.models.WorkflowStatus

/**
 * A workflow as a run starts it: what the run copies (its name and version) and what it checks (its status, the inputs
 * it declares).
 *
 * @property id The workflow.
 * @property name Its name.
 * @property version Its saved version, which the run records.
 * @property status Proposed, accepted or edited.
 * @property inputs The inputs it declares.
 */
data class RunWorkflow(
    val id: String,
    val name: String,
    val version: Int,
    val status: WorkflowStatus,
    val inputs: List<WorkflowInput>,
)
