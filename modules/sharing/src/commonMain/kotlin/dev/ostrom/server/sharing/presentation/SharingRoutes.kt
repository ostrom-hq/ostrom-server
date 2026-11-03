// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.secrets.presentation.toIdOr
import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountNotFoundException
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.EnrollAccountPayload
import dev.ostrom.server.sharing.domain.InvalidAccountException
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.SharedAccountView
import dev.ostrom.server.sharing.domain.UpdateAccountPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The shared accounts routes: any member shares its own subscription and sees the pool (neutral labels for accounts
 * of others), admins and owners change and remove; people are named by login, as the contract names them. Reading
 * needs `accounts:read`, writing `accounts:write`; an agent's or a device's credential reaches none of them (S2-03,
 * S2-21), and no answer ever holds an account's tokens.
 *
 * @param dependencies The use cases.
 */
fun Route.sharingRoutes(dependencies: SharingRoutesDependencies) = with(dependencies) {
    authenticated {
        get<AccountsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ)
            call.respond(SharedAccountsResponse(list(caller).map { it.toResponse(caller) }))
        }
        post<AccountsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            // The contract's body names a secret; without one, the tokens' body (imports, end-to-end tests).
            val body = call.receive<JsonObject>()
            val account = if (body["credentialSecret"].let { it != null && it !is JsonNull }) {
                enroll(caller, body.decoded(EnrollSharedAccountRequest.serializer()).toPayload())
            } else {
                share(caller, body.decoded(ShareAccountRequest.serializer()).toPayload())
            }
            call.respond(HttpStatusCode.Created, view(caller, account).toResponse(caller))
        }
        patch<AccountsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val id = params.id.toIdOr(::AccountNotFoundException)
            val request = call.receive<UpdateAccountRequest>()
            val payload = UpdateAccountPayload(
                label = request.label,
                priority = request.priority,
                disabled = request.paused,
                limits = request.limitsChange(),
                sharedWith = request.sharedWith,
            )
            call.respond(view(caller, update(caller, id, payload)).toResponse(caller))
        }
        delete<AccountsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            remove(caller, params.id.toIdOr(::AccountNotFoundException))
            call.respond(HttpStatusCode.NoContent)
        }
        get<CapacityApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ)
            val views = list(caller)
            val pools = views.groupBy { it.account.provider }.map { (provider, accounts) ->
                val ready = accounts.count { it.account.status == AccountStatus.READY }
                CapacityPoolDto(provider.wire, accounts.size, ready, accounts.sumOf { it.inFlight })
            }
            call.respond(CapacityResponse(views.map { it.toResponse(caller) }, pools, clock.now().toString()))
        }
    }
}

/**
 * How a change moves the current limits: the keys of `limits` replace theirs (the others stay), then
 * `dailyAllocationUsd` sets the daily allocation; `sharedWith` is the use case's (members are named by login).
 * Malformed input is refused before the account is read.
 */
private fun UpdateAccountRequest.limitsChange(): ((AccountLimits) -> AccountLimits)? {
    val patch = limits
    val daily = dailyAllocationUsd?.let(::usdToMicros)
    patch?.let { AccountLimits().patchedBy(it) }
    return if (patch == null && daily == null) {
        null
    } else {
        { current ->
            val patched = if (patch == null) current else current.patchedBy(patch)
            if (daily == null) patched else patched.copy(dailyBudgetMicros = daily)
        }
    }
}

/** These limits with the keys of [patch] replaced. */
private fun AccountLimits.patchedBy(patch: JsonObject): AccountLimits {
    val merged = JsonObject(Json.encodeToJsonElement(AccountLimitsDto.serializer(), toDto()).jsonObject + patch)
    val dto = try {
        Json.decodeFromJsonElement(AccountLimitsDto.serializer(), merged)
    } catch (_: SerializationException) {
        throw InvalidAccountException(
            "limits holds maxConcurrent, utilizationCeiling, monthlyBudgetMicros, " +
                "allowedMembers, dailyBudgetMicros.",
        )
    }
    return dto.toDomain()
}

private fun EnrollSharedAccountRequest.toPayload() = EnrollAccountPayload(
    provider = providerNamed(provider),
    label = label,
    credentialSecret = credentialSecret,
    sharedWith = sharedWith,
    dailyBudgetMicros = dailyAllocationUsd?.let(::usdToMicros),
)

private fun providerNamed(value: String) =
    AccountProvider.named(value) ?: throw InvalidAccountException("provider is claude or codex.")

private fun ShareAccountRequest.toPayload() = ShareAccountPayload(
    provider = providerNamed(provider),
    label = label,
    email = email,
    providerAccountId = providerAccountId,
    accessToken = accessToken,
    // Only an enrolled secret holds a long-lived token: the tokens' body always carries a refresh token.
    refreshToken = refreshToken.ifEmpty { throw InvalidAccountException("The tokens are malformed.") },
    expiresAt = try {
        Instant.parse(expiresAt)
    } catch (_: IllegalArgumentException) {
        throw InvalidAccountException("expiresAt is an ISO 8601 instant.")
    },
    limits = limits.toDomain(),
)

private fun AccountLimitsDto.toDomain() = AccountLimits(
    maxConcurrent = maxConcurrent,
    utilizationCeiling = utilizationCeiling,
    monthlyBudgetMicros = monthlyBudgetMicros,
    allowedMembers = allowedMembers?.map {
        Uuid.parseOrNull(it)
            ?: throw InvalidAccountException("allowedMembers holds member ids.")
    }?.toSet(),
    dailyBudgetMicros = dailyBudgetMicros,
)

private fun AccountLimits.toDto() = AccountLimitsDto(
    maxConcurrent,
    utilizationCeiling,
    monthlyBudgetMicros,
    allowedMembers?.map {
        it.toString()
    }?.sorted(),
    dailyBudgetMicros,
)

private fun SharedAccountView.toResponse(caller: Caller) = SharedAccountResponse(
    id = account.id.toString(),
    provider = account.provider.wire,
    label = displayLabel,
    email = account.email.takeIf { identityVisible },
    providerAccountId = account.providerAccountId.takeIf { identityVisible },
    owner = owner,
    sharedWith = sharedWith,
    paused = account.status == AccountStatus.DISABLED,
    mine = identityVisible && account.ownerMemberId == caller.memberId,
    priority = account.priority,
    status = account.status.name.lowercase(),
    limits = if (identityVisible) {
        account.limits.toDto()
    } else {
        AccountLimitsDto(
            account.limits.maxConcurrent,
            account.limits.utilizationCeiling,
        )
    },
    cooldownUntil = account.cooldownUntil?.toString(),
    windows = windows.map {
        QuotaWindowDto(it.window, it.utilization, it.resetsAt?.toString(), it.observedAt.toString())
    },
    inFlight = inFlight,
    spentMicros = spentMicros,
    createdAt = account.createdAt.toString(),
    dailyAllocationUsd = account.limits.dailyBudgetMicros?.takeIf { identityVisible }?.let { it / MICROS_PER_USD },
)
