// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.ModelUsage
import run.ostrom.server.budgets.domain.PoolSizeReport
import run.ostrom.server.budgets.domain.WindowUsageSplit
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpHeaders
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * TeamCode's `GET /api/v1/pools/size` (`capacity.poolsize.v1`): owners and admins of the org, a person signed in (a
 * browser session, the unlocked app or `ostrom login`) or a service key holding `team:read`; any other credential is
 * refused before anything is read, and a member by the service. Never cached.
 *
 * @param dependencies The sizing and who calls.
 */
fun Route.poolSizeRoutes(dependencies: PoolSizeRoutesDependencies) = with(dependencies) {
    authenticated {
        get<PoolSizeApi> {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val principal = call.requirePrincipal()
            val teamKey = principal.kind == PrincipalKind.SERVICE && ServiceKeyScopes.TEAM_READ in principal.scopes
            if (!teamKey && !principal.mayWrite(WriteAccess.PERSON)) throw PoolSizeForbiddenException()
            val caller = call.requireCaller(callers, BudgetScopes.READ, ServiceKeyScopes.TEAM_READ)
            call.respond(sizing.report(caller).toResponse())
        }
    }
}

/** A credential that is neither a person signed in nor a `team:read` key: a 403. */
class PoolSizeForbiddenException :
    OstromException(
        ErrorKind.FORBIDDEN,
        "The pool size is read by an owner or an admin signed in, or a key holding team:read.",
    )

/** The contract's `PoolSize`. */
internal fun PoolSizeReport.toResponse() = PoolSizeResponse(
    enabled = enabled,
    manualDailyMicros = manualDailyMicros,
    automaticDailyMicros = automatic?.micros,
    automaticComputedAt = automatic?.let { it.computedAt.toString() },
    effectiveDailyMicros = effectiveDailyMicros,
    source = if (usesAutomatic) "automatic" else "manual",
    estimatedDailyMicros = estimate.dailyMicros,
    reason = estimate.reason,
    unpricedModels = unpricedModels,
    accounts = estimate.accounts.map { share ->
        PoolSizeAccountResponse(
            accountId = share.accountId.toString(),
            status = share.status.name.lowercase(),
            dailyMicros = share.dailyMicros,
            binding = share.binding,
            capacityDailyMicros = share.capacityDailyMicros,
            windows = capacities[share.accountId].orEmpty().map {
                PoolSizeWindowCapacityResponse(it.window, it.fullWindowMicros, it.capacityMicros, it.samples)
            },
            usage = usage[share.accountId].orEmpty().map { it.toResponse() },
        )
    },
    capacityDailyMicros = estimate.capacityDailyMicros,
    capacityUnknownAccounts = estimate.capacityUnknownAccounts,
    capacityDailyTokens = capacityDailyTokens,
    tokensPerMicro = mix.tokensPerMicro,
    outsideWeeklyMicros = outsideWeeklyMicros,
    outsideUnknownAccounts = outsideUnknownAccounts,
    models = mix.models.map { it.toResponse() },
)

private fun WindowUsageSplit.toResponse() = PoolSizeWindowUsageResponse(
    window = window,
    since = since.toString(),
    observedAt = observedAt.toString(),
    resetsAt = resetsAt.toString(),
    observedUtilization = observedShare,
    ostromMicros = ostromMicros,
    ostromUtilization = ostromShare,
    outsideUtilization = outsideShare,
    outsideMicros = outsideMicros,
    remainingMicros = remainingMicros,
)

private fun ModelUsage.toResponse() = PoolSizeModelResponse(
    provider = provider,
    model = model,
    inputTokens = tokens.input,
    cacheWriteTokens = tokens.cacheWrite,
    cacheReadTokens = tokens.cacheRead,
    outputTokens = tokens.output,
    inputMicros = cost?.inputMicros,
    cacheWriteMicros = cost?.cacheWriteMicros,
    cacheReadMicros = cost?.cacheReadMicros,
    outputMicros = cost?.outputMicros,
    totalMicros = cost?.totalMicros,
    unpricedTokens = unpricedTokens,
)
