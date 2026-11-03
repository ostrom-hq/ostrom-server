// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.usecases

import dev.ostrom.server.compiler.domain.models.ApproverGate
import dev.ostrom.server.compiler.domain.models.ApproverLine
import dev.ostrom.server.compiler.domain.models.ApproversGraph
import dev.ostrom.server.compiler.domain.models.CatalogGate
import kotlin.uuid.Uuid

/** Who approves what in a repository, read from the same catalogue the gates decide on. */
interface ApproversGraphUseCase {
    /**
     * Builds the graph.
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @return Every approver with their gates, the gates nobody answers and the actions no gate covers.
     */
    suspend operator fun invoke(orgId: Uuid, repo: String?): ApproversGraph
}

/**
 * [ApproversGraphUseCase] on [GatesCatalogUseCase]: no other read, so the graph never disagrees with a gate. The
 * organization's graph also has the gates limited to some repositories (PARCOURS 3.3), read from their catalogues.
 *
 * @param catalog The gates catalogue.
 */
class ApproversGraphUseCaseImpl(private val catalog: GatesCatalogUseCase) : ApproversGraphUseCase {
    override suspend fun invoke(orgId: Uuid, repo: String?): ApproversGraph {
        val catalogues = if (repo == null) catalog.everywhere(orgId).values else listOf(catalog(orgId, repo))
        val byGate = catalogues.flatten()
            .mapNotNull { entry -> entry.gate?.let { it to entry.action.cls } }
            .groupBy({ (gate, _) -> gate.workflowId to gate.nodeId })
            .map { (_, covered) -> covered.first().first.let { it to it.toApproverGate(covered) } }
            .sortedWith(compareBy({ it.second.workflowId }, { it.second.nodeId }))
        val approvers = byGate.flatMap { (gate, view) -> gate.approvers.distinct().map { it to view } }
            .groupBy({ it.first }, { it.second })
            .map { (approver, gates) -> ApproverLine(approver, gates) }
            .sortedBy { it.approver }
        // An action is ungated when no catalogue has a gate for it, the organization's nor a repository's.
        val ungated = catalogues.map { entries -> entries.filter { it.gate == null }.map { it.action.cls }.toSet() }
            .reduce { left, right -> left intersect right }
        return ApproversGraph(
            approvers = approvers,
            unanswered = byGate.filter { it.first.approvers.isEmpty() }.map { it.second },
            ungated = ungated.sorted(),
        )
    }

    private fun CatalogGate.toApproverGate(covered: List<Pair<CatalogGate, String>>) =
        ApproverGate(workflowId, workflowName, nodeId, label, enforce, covered.map { it.second }.distinct().sorted())
}
