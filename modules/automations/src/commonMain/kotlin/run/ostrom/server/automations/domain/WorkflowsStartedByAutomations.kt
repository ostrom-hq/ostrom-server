// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.workflows.AutomationUsage
import kotlin.uuid.Uuid

/**
 * [AutomationUsage] on the automations: a workflow that an automation's action starts is not deleted under it
 * (`409 workflow_in_use`, naming the automation, TC-AUT-5).
 *
 * @param repository The automations.
 */
class WorkflowsStartedByAutomations(private val repository: AutomationsRepository) : AutomationUsage {
    override suspend fun automationStarting(orgId: Uuid, workflowId: String): String? =
        repository.automationStarting(orgId, workflowId)
}
