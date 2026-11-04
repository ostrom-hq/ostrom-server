// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import dev.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import dev.ostrom.server.kernel.workflows.WorkflowDirectory
import kotlin.uuid.Uuid

/**
 * The [WorkflowDirectory] of the server: the workflows this module keeps, so that a launch names the one it plays.
 *
 * @param workflows The organization's workflows.
 */
class CompilerWorkflowDirectory(private val workflows: WorkflowsRepository) : WorkflowDirectory {
    override suspend fun name(orgId: Uuid, workflowId: String): String? =
        workflows.workflow(orgId, workflowId)?.let { it.doc.workflow.name }
}
