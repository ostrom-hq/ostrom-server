// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.rules.domain.usecases.GetGatePolicyUseCase
import kotlin.uuid.Uuid

/**
 * The gates module's [GatePolicies], read from the organization's decided policy: what `PUT /api/v1/gates/policy`
 * sets is what every gate of the organization applies, at its next answer.
 *
 * @param policy Reads the setting.
 */
class RulesGatePolicies(private val policy: GetGatePolicyUseCase) : GatePolicies {
    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy = policy(orgId)
}
