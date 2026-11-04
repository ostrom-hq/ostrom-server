// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import dev.ostrom.server.compiler.domain.models.CatalogGate
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import kotlin.uuid.Uuid

/**
 * The gate of an action for the gates module: the catalogue's gate for the action class, the one the session package
 * compiled into its hooks (`ostrom/gates.json`), so the hook and the server never disagree.
 *
 * @param pins The compiler's storage, fetched at the first call, which holds the pinned package of a repository.
 * @param catalog The gates catalogue, fetched at the first call.
 */
class CatalogGateNodes(
    private val pins: (() -> CompilerRepository)? = null,
    private val catalog: () -> GatesCatalogUseCase,
) : GateNodeResolver {
    override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? =
        resolver(orgId, repo)(actionClass)

    // The catalogue is compiled once (the workflows, the rules, the settings) for every class an ask looks up.
    override suspend fun resolver(orgId: Uuid, repo: String?): suspend (String) -> GateNode? {
        val entries = catalog()(orgId, repo)
        return { actionClass ->
            val gate = entries.firstOrNull { it.action.cls == actionClass }?.gate
            gate?.toNode()
        }
    }

    override suspend fun enforcesAny(orgId: Uuid, repo: String?): Boolean =
        catalog()(orgId, repo).any { it.gate?.enforce == true }

    override suspend fun policyStamp(orgId: Uuid, repo: String?): String? =
        pins?.let { it().pin(orgId, repo)?.contentDigest }

    private fun CatalogGate.toNode(): GateNode = GateNode(
        workflowId = workflowId,
        nodeId = nodeId,
        name = label,
        approvers = approvers,
        enforced = enforce,
        workflowName = workflowName,
        workflowStatus = status,
        basis = basis,
        reason = reason,
    )
}
