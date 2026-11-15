// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.secrets.presentation.toIdOr
import run.ostrom.server.sharing.domain.AccountNotFoundException
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.ForecastAccount
import run.ostrom.server.sharing.domain.ForecastSeries
import run.ostrom.server.sharing.domain.ForecastSnapshot
import run.ostrom.server.sharing.domain.QuotaWindow
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * TeamCode's Forecasts page as JSON (`getForecasts`): owners and admins of the org only, and only a person signed in (a
 * browser session, the unlocked app or the token of `ostrom login`, which holds `accounts:read` for `ostrom status`
 * when issued after #587); a key, an agent's or a device's credential is refused before anything is read, and a
 * member by the service. Never cached.
 *
 * @param dependencies The forecasts and who calls.
 */
fun Route.forecastRoutes(dependencies: ForecastRoutesDependencies) = with(dependencies) {
    authenticated {
        get<ForecastsApi> {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (!call.requirePrincipal().mayWrite(WriteAccess.PERSON)) throw ForecastsForbiddenException()
            val account = call.singleForecastAccount()
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ)
            val id = account?.toIdOr(::AccountNotFoundException)
            call.respond(forecasts.load(caller, id).toResponse())
        }
    }
}

/** The `account` query parameter, or null; a 400 `invalid_account` when it is given more than once. */
internal fun ApplicationCall.singleForecastAccount(): String? {
    val accounts = request.queryParameters.getAll("account") ?: return null
    if (accounts.size != 1) throw InvalidForecastAccountException()
    return accounts.single()
}

/** `account` given more than once: a 400 `invalid_account`, as TeamCode. */
class InvalidForecastAccountException :
    OstromException(ErrorKind.INVALID_REQUEST, "Choose one account.", "invalid_account")

/** A credential that is not a person signed in: a 403. */
class ForecastsForbiddenException :
    OstromException(
        ErrorKind.FORBIDDEN,
        "The forecasts are read by an owner or an admin signed in: a key, an agent or a device cannot read them.",
    )

private fun ForecastSnapshot.toResponse() = ForecastSnapshotResponse(
    generatedAt = generatedAt.toString(),
    accounts = accounts.map { it.toResponse() },
    account = account?.toResponse(),
    windows = windows.map { it.toResponse() },
    truncated = truncated,
    pressureThreshold = pressureThreshold,
)

private fun ForecastAccount.toResponse() = ForecastAccountResponse(
    id = id.toString(),
    provider = provider.wire,
    label = label,
    status = wireStatus(status, ownership),
    paused = status == AccountStatus.DISABLED,
    email = email,
)

private fun ForecastSeries.toResponse() = ForecastWindowResponse(
    window = quota.window,
    utilization = quota.utilization,
    observedAt = quota.observedAt.toString(),
    resetsAt = quota.resetsAt?.toString(),
    state = forecast.state.wire,
    pointsPerHour = forecast.pointsPerHour,
    reachesThresholdAt = forecast.reachesThresholdAt?.toString(),
    segments = segments.map { segment -> segment.map { it.toSample() } },
    learningSamples = learningSamples,
    learningSpanMillis = learningSpan.inWholeMilliseconds,
)

private fun QuotaWindow.toSample() = ForecastSampleResponse(utilization, observedAt.toString(), resetsAt?.toString())
