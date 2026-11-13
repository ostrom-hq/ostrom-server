// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.workflows

import kotlin.uuid.Uuid

/**
 * Tells whether an automation starts a workflow, so that a workflow an automation starts is never deleted under it
 * (`workflow_in_use`, naming the automation). The automations module binds it; unbound, no automation starts one.
 */
fun interface AutomationUsage {
    /**
     * The name of an automation that starts a workflow.
     *
     * @param orgId The organization.
     * @param workflowId The workflow's id.
     *
     * @return The automation's name, or null when none starts the workflow.
     */
    suspend fun automationStarting(orgId: Uuid, workflowId: String): String?
}
