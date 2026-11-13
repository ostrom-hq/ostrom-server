// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.models

/**
 * Who approves what in a repository: the gates catalogue turned around, from the approvers' side.
 *
 * @property approvers Every approver, by name, with the gates they answer.
 * @property unanswered The gates that name no approver: their actions can never run.
 * @property ungated The action classes no gate covers, by class.
 */
data class ApproversGraph(
    val approvers: List<ApproverLine>,
    val unanswered: List<ApproverGate>,
    val ungated: List<String>,
)

/**
 * One approver and the gates they answer.
 *
 * @property approver The approver, as the workflow names them.
 * @property gates Their gates, by workflow then node.
 */
data class ApproverLine(val approver: String, val gates: List<ApproverGate>)

/**
 * A gate and the actions it covers.
 *
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The gate's label.
 * @property enforce False while the workflow is only proposed.
 * @property actions The action classes it covers, by class.
 */
data class ApproverGate(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val enforce: Boolean,
    val actions: List<String>,
)
