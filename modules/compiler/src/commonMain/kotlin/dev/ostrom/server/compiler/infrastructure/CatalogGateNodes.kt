// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import kotlin.uuid.Uuid

/**
 * The gate of an action for the gates module: the catalogue's gate for the action class, the one the session package
 * compiled into its hooks (`ostrom/gates.json`), so the hook and the server never disagree.
 *
 * @param catalog The gates catalogue, fetched at the first call.
 */
class CatalogGateNodes(private val catalog: () -> GatesCatalogUseCase) : GateNodeResolver {
    override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? {
        val gate = catalog()(orgId, repo).firstOrNull { it.action.cls == actionClass }?.gate ?: return null
        return GateNode(
            workflowId = gate.workflowId,
            nodeId = gate.nodeId,
            name = gate.label,
            approvers = gate.approvers,
            enforced = gate.enforce,
            workflowName = gate.workflowName,
            workflowStatus = gate.status,
            basis = gate.basis,
            reason = gate.reason,
        )
    }
}
