// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.domain

import run.ostrom.server.kernel.auth.ModelAccess
import kotlin.uuid.Uuid

/**
 * Why a member may not use a model: the gateway's refusal of a call, and a launch's (via [PolicyModelAccess]).
 *
 * @param model The model refused.
 *
 * @return The reason.
 */
fun modelNotAllowed(model: String): String = "Your organization does not allow the model $model."

/**
 * [ModelAccess] on the org's model policy, the same check as a model call: a launch is refused a model the gateway
 * would refuse, with the same reason.
 *
 * @param settings The org's settings and policy.
 */
class PolicyModelAccess(private val settings: GatewaySettingsRepository) : ModelAccess {
    override suspend fun refusal(orgId: Uuid, memberId: Uuid, model: String): String? =
        modelNotAllowed(model).takeUnless { settings.policy(orgId).allows(memberId, model) }
}
