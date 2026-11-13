// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.gates.domain.services.GatePolicies
import run.ostrom.server.rules.domain.usecases.GetGatePolicyUseCase
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
