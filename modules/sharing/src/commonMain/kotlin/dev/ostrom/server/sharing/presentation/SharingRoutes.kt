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
import dev.ostrom.server.sharing.domain.InvalidAccountException
import dev.ostrom.server.sharing.domain.ShareAccountPayload
import dev.ostrom.server.sharing.domain.SharedAccount
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The shared accounts routes: any member shares its own subscription and sees the pool (neutral labels for accounts
 * of others), admins and owners change and remove. Reading needs `accounts:read`, writing `accounts:write`; an agent's
 * or a device's credential reaches none of them (S2-03, S2-21), and no answer ever holds an account's tokens.
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
            val account = share(caller, call.receive<ShareAccountRequest>().toPayload())
            call.respond(HttpStatusCode.Created, account.toResponse(caller))
        }
        patch<AccountsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val id = params.id.toIdOr(::AccountNotFoundException)
            val request = call.receive<UpdateAccountRequest>()
            val payload = UpdateAccountPayload(
                label = request.label,
                priority = request.priority,
                disabled = request.paused,
                limits = limitsOf(caller, id, request),
            )
            call.respond(update(caller, id, payload).toResponse(caller))
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
 * The limits a change sets: the requested ones, with `sharedWith` applied on top (on the current limits when none are
 * requested); an empty `sharedWith` opens the account to the whole org.
 */
private suspend fun SharingRoutesDependencies.limitsOf(caller: Caller, id: Uuid, request: UpdateAccountRequest) =
    request.sharedWith?.let { members ->
        (request.limits?.toDomain() ?: currentLimits(caller, id)).copy(allowedMembers = memberIds(members))
    } ?: request.limits?.toDomain()

/** The limits of an account the caller may change; not found otherwise, so existence does not leak. */
private suspend fun SharingRoutesDependencies.currentLimits(caller: Caller, accountId: Uuid): AccountLimits =
    list(caller).firstOrNull { it.account.id == accountId && it.identityVisible }?.account?.limits
        ?: throw AccountNotFoundException()

/** Member ids of `sharedWith`; an empty list opens the account to the whole org. */
private fun memberIds(values: List<String>): Set<Uuid>? = values.takeIf { it.isNotEmpty() }?.map {
    Uuid.parseOrNull(it)
        ?: throw InvalidAccountException("sharedWith holds member ids.")
}?.toSet()

private fun ShareAccountRequest.toPayload() = ShareAccountPayload(
    provider =
    AccountProvider.entries.firstOrNull { it.wire == provider }
        ?: throw InvalidAccountException("provider is claude or codex."),
    label = label,
    email = email,
    providerAccountId = providerAccountId,
    accessToken = accessToken,
    refreshToken = refreshToken,
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
)

private fun AccountLimits.toDto() = AccountLimitsDto(
    maxConcurrent,
    utilizationCeiling,
    monthlyBudgetMicros,
    allowedMembers?.map {
        it.toString()
    }?.sorted(),
)

private fun SharedAccount.toResponse(caller: Caller) =
    SharedAccountView(this, label, true, emptyList(), 0, 0).toResponse(caller)

private fun SharedAccountView.toResponse(caller: Caller) = SharedAccountResponse(
    id = account.id.toString(),
    provider = account.provider.wire,
    label = displayLabel,
    email = account.email.takeIf { identityVisible },
    providerAccountId = account.providerAccountId.takeIf { identityVisible },
    owner = account.ownerMemberId?.toString()?.takeIf { identityVisible }.orEmpty(),
    sharedWith = account.limits.allowedMembers?.map { it.toString() }?.sorted()?.takeIf { identityVisible }.orEmpty(),
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
)
