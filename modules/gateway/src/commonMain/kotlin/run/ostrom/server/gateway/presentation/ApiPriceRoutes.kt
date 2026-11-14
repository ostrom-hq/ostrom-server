// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.ApiPriceChange
import run.ostrom.server.gateway.domain.ApiPrices
import run.ostrom.server.gateway.domain.InvalidApiPriceException
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The routes of the org's price table (TeamCode's `/policy/api-prices`), inside an authenticated route: any member
 * reads it, an owner or admin signed in to the console replaces it or loads the built-in prices, each change audited.
 *
 * @param dependencies The use cases.
 */
fun Route.apiPriceRoutes(dependencies: GatewayRoutesDependencies) = with(dependencies) {
    get<ApiPricesApi> {
        val caller = call.requireCaller(
            callers,
            RelayScopes.INFERENCE_READ,
            RelayScopes.AGENT_SESSION,
            RelayScopes.USAGE_READ,
        )
        val view = getApiPrices(caller)
        call.respond(ApiPriceTableDto(view.prices.map { it.toDto() }, view.defaults))
    }
    put<ApiPricesApi> {
        val caller = call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)
        call.requireRecentSignIn("change the price table")
        val principal = call.requirePrincipal()
        val request = call.receive<ApiPriceTableDto>()
        val change = putApiPrices(caller, request.prices.map { it.toPrice() })
        audit.record(change.record(caller.orgId, principal, "capacity.api_price_changed"))
        call.respond(ApiPriceTableDto(change.after.map { it.toDto() }))
    }
    post<ApiPriceDefaultsApi> {
        val caller = call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)
        call.requireRecentSignIn("change the price table")
        val principal = call.requirePrincipal()
        val change = loadApiPriceDefaults(caller)
        audit.record(change.record(caller.orgId, principal, "capacity.api_prices_defaults_loaded"))
        call.respond(ApiPriceTableDto(change.after.map { it.toDto() }))
    }
}

private fun ApiPriceChange.record(
    orgId: Uuid,
    actor: AuthenticatedPrincipal,
    event: String,
) = OrgAuditRecord(
    orgId,
    actor,
    event,
    "price_table",
    "api-prices",
    auditDetails("rows_before" to before.size, "rows_after" to after.size),
)

private fun ModelPrice.toDto() = ApiPriceDto(
    provider,
    model,
    ApiPrices.dateOf(effectiveFromDay),
    input,
    cacheWrite,
    cacheRead,
    output,
)

private fun ApiPriceDto.toPrice() = ModelPrice(
    provider = provider,
    model = model,
    input = inputMicros,
    cacheWrite = cacheWriteMicros,
    cacheRead = cacheReadMicros,
    output = outputMicros,
    effectiveFromDay = ApiPrices.dayOf(effectiveFrom)
        ?: throw InvalidApiPriceException("Use an effective date written YYYY-MM-DD."),
)
