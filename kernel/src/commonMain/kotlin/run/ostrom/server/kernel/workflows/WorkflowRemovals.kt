// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.workflows

import kotlin.uuid.Uuid

/**
 * Told when an organization deletes one of its workflows, so that what other modules keep about it goes too (a
 * workflow's budgets): a workflow created again under the same id starts without them.
 */
fun interface WorkflowRemovals {
    /**
     * Forgets what is kept about a deleted workflow.
     *
     * @param orgId The organization.
     * @param workflowId The deleted workflow's id.
     */
    suspend fun removed(orgId: Uuid, workflowId: String)
}
