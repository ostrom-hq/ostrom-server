// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.workflowruns.domain.models.RunGraph
import dev.ostrom.server.workflowruns.domain.models.RunWorkflow
import dev.ostrom.server.workflowruns.domain.services.RunWorkflows
import kotlin.uuid.Uuid

/**
 * [RunWorkflows] on the compiler module's workflows, as saved: their decoded document, its graph and saved version.
 *
 * @param compiler The compiler's repository.
 */
class CompilerRunWorkflows(private val compiler: CompilerRepository) : RunWorkflows {
    override suspend fun find(orgId: Uuid, id: String): RunWorkflow? = compiler.workflow(orgId, id)?.let { stored ->
        val workflow = stored.doc.workflow
        val graph = RunGraph(workflow.start, workflow.nodes, workflow.edges)
        RunWorkflow(workflow.id, workflow.name, stored.version, workflow.status, workflow.inputs, graph)
    }
}
