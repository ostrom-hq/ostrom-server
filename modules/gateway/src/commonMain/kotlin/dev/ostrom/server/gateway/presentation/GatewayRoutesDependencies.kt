// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.presentation

import dev.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.GetModelPolicyUseCase
import dev.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.PutModelPolicyUseCase
import dev.ostrom.server.gateway.domain.RelayInferenceUseCase
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.secrets.domain.CallerResolver
import kotlin.time.Clock

/**
 * What the gateway routes need.
 *
 * @property tokens Authenticates the inference calls, which also accept `x-api-key`.
 * @property callers Resolves the member of a credential.
 * @property relay Relays a model call.
 * @property getSettings Reads the settings.
 * @property putSettings Changes them.
 * @property getPolicy Reads the model policy.
 * @property putPolicy Changes it.
 * @property clock The time.
 * @property audit Writes a change of the policy or the settings to the organization's audit log.
 */
data class GatewayRoutesDependencies(
    val tokens: TokenAuthenticator,
    val callers: CallerResolver,
    val relay: RelayInferenceUseCase,
    val getSettings: GetGatewaySettingsUseCase,
    val putSettings: PutGatewaySettingsUseCase,
    val getPolicy: GetModelPolicyUseCase,
    val putPolicy: PutModelPolicyUseCase,
    val clock: Clock,
    val audit: OrgAuditTrail,
)
