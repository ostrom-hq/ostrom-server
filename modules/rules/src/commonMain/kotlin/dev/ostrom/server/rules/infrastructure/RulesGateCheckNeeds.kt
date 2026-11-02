// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateCheckNeed
import dev.ostrom.server.gates.domain.services.GateCheckNeeds
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import kotlin.uuid.Uuid

/**
 * The checks a rule's gate needs, for the gates module: the `gate.checks` of the rule whose one-gate workflow
 * ([RulesCompilerSources.WORKFLOW_PREFIX] and its id) covers the action. A workflow's own gate needs none here.
 *
 * @param repository The rules, read in the organization of the gate.
 */
class RulesGateCheckNeeds(private val repository: RulesRepository) : GateCheckNeeds {
    override suspend fun of(orgId: Uuid, node: GateNode): List<GateCheckNeed> {
        val id = node.workflowId.removePrefix(RulesCompilerSources.WORKFLOW_PREFIX)
        if (id == node.workflowId) return emptyList()
        // A rule deleted, or its gate removed, since the catalogue was read needs nothing: its gate no longer exists.
        val gate = repository.rule(orgId, id)?.gate ?: return emptyList()
        return gate.checks.map { GateCheckNeed(it.check, it.attested) }
    }
}
