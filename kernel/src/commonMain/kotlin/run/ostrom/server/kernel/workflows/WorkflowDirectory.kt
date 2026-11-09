// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.workflows

import kotlin.uuid.Uuid

/**
 * The organization's workflows, by id. The compiler module binds it on the workflows it keeps, so that a launch names
 * the workflow it plays (`createLaunch.workflowId`) and the spending names a workflow, without depending on that
 * module. Unbound, no workflow is known.
 */
fun interface WorkflowDirectory {
    /**
     * The name of one of the organization's workflows.
     *
     * @param orgId The organization.
     * @param workflowId The workflow's id.
     *
     * @return Its name, or null when the organization has no such workflow.
     */
    suspend fun name(orgId: Uuid, workflowId: String): String?
}
