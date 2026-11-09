// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.GetModelPolicyUseCase
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.PutModelPolicyUseCase
import run.ostrom.server.gateway.domain.RelayInferenceUseCase
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.secrets.domain.CallerResolver
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
