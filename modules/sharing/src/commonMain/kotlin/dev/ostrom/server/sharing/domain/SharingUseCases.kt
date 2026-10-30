// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A member shares one of its subscriptions (or an admin adds one) with tokens read on the member's machine by the CLI.
 *
 * @property provider The subscription.
 * @property label The name admins see.
 * @property email The account's address, if given.
 * @property providerAccountId The provider's account id: sharing it again replaces its tokens.
 * @property accessToken The OAuth access token.
 * @property refreshToken The OAuth refresh token.
 * @property expiresAt When the access token expires.
 * @property limits Its limits.
 */
data class ShareAccountPayload(
    val provider: AccountProvider,
    val label: String,
    val email: String?,
    val providerAccountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val limits: AccountLimits = AccountLimits(),
) {
    override fun toString(): String =
        "ShareAccountPayload(provider=$provider, label=$label, providerAccountId=$providerAccountId, tokens=[REDACTED])"
}

/**
 * Changes an account; null fields stay.
 *
 * @property label New label.
 * @property priority New priority.
 * @property disabled Turns it off or back on (a RECONNECT_REQUIRED account comes back only by sharing it again).
 * @property limits The new limits from the current ones, so a change of one limit keeps the others.
 */
data class UpdateAccountPayload(
    val label: String? = null,
    val priority: Int? = null,
    val disabled: Boolean? = null,
    val limits: ((AccountLimits) -> AccountLimits)? = null,
)

/**
 * An account as the caller may see it.
 *
 * @property account The account; identity fields blanked unless [identityVisible].
 * @property displayLabel The label, or "Account N" for members other than its owner.
 * @property identityVisible Whether the caller is an admin or its owner.
 * @property windows Current provider windows.
 * @property inFlight Requests in flight on this replica.
 * @property spentMicros Equivalent API cost served this month.
 */
data class SharedAccountView(
    val account: SharedAccount,
    val displayLabel: String,
    val identityVisible: Boolean,
    val windows: List<QuotaWindow>,
    val inFlight: Int,
    val spentMicros: Long,
)

/** Shares an account. */
interface ShareAccountUseCase {
    /** Shares it; returns the stored account. */
    suspend operator fun invoke(caller: Caller, payload: ShareAccountPayload): SharedAccount
}

/** Lists the org's accounts. */
interface ListSharedAccountsUseCase {
    /** Lists them, identities visible to admins and owners only. */
    suspend operator fun invoke(caller: Caller): List<SharedAccountView>
}

/** Changes an account. */
interface UpdateSharedAccountUseCase {
    /** Changes it: admins, or its owner. */
    suspend operator fun invoke(caller: Caller, id: Uuid, payload: UpdateAccountPayload): SharedAccount
}

/** Removes an account. */
interface RemoveSharedAccountUseCase {
    /** Removes it: admins, or its owner. */
    suspend operator fun invoke(caller: Caller, id: Uuid)
}

/** Another member already shares this provider account. */
class AccountOwnedByAnotherMemberException :
    OstromException(ErrorKind.CONFLICT, "Another member already shares this account.")

/** [ShareAccountUseCase]. */
class ShareAccountUseCaseImpl(
    private val repository: SharedAccountRepository,
    private val cipher: AccountTokenCipher,
    private val clock: Clock,
) : ShareAccountUseCase {
    override suspend fun invoke(caller: Caller, payload: ShareAccountPayload): SharedAccount {
        SharingRules.requireLabel(payload.label)
        SharingRules.requireLimits(payload.limits)
        SharingRules.requireTokens(payload.accessToken, payload.refreshToken, payload.providerAccountId)
        SharingRules.requireEmail(payload.email)
        val existing = repository.list(caller.orgId)
        val same = existing.firstOrNull {
            it.provider == payload.provider && it.providerAccountId == payload.providerAccountId
        }
        when {
            same == null && existing.size >= SharingRules.MAX_ACCOUNTS -> throw TooManyAccountsException()
            same != null && !caller.mayChange(same) -> throw AccountOwnedByAnotherMemberException()
            else -> Unit
        }
        val account = same.sharedAgain(caller, payload, clock)
        val credentials = AccountCredentials(payload.accessToken, payload.refreshToken, payload.expiresAt)
        return repository.upsert(account, cipher.seal(credentials, account.orgId, account.id))
    }
}

/**
 * The account a share stores: a new one owned by the caller with the requested limits, or [this] with new tokens and
 * label (its owner, priority, limits, creation and a DISABLED status kept: an admin's restrictions survive the owner
 * sharing again after a reconnection; limits change through an update).
 */
private fun SharedAccount?.sharedAgain(caller: Caller, payload: ShareAccountPayload, clock: Clock): SharedAccount {
    val fresh = SharedAccount(
        id = Uuid.random(),
        orgId = caller.orgId,
        provider = payload.provider,
        label = payload.label,
        email = payload.email,
        providerAccountId = payload.providerAccountId,
        ownerMemberId = caller.memberId,
        priority = 0,
        status = AccountStatus.READY,
        limits = payload.limits,
        cooldownUntil = null,
        createdAt = clock.now(),
    )
    return if (this == null) {
        fresh
    } else {
        fresh.copy(
            id = id,
            ownerMemberId = ownerMemberId ?: caller.memberId,
            priority = priority,
            limits = limits,
            status = if (status == AccountStatus.DISABLED) AccountStatus.DISABLED else AccountStatus.READY,
            createdAt = createdAt,
        )
    }
}

/** [ListSharedAccountsUseCase]. */
class ListSharedAccountsUseCaseImpl(
    private val repository: SharedAccountRepository,
    private val quotas: QuotaWindowRepository,
    private val pool: SharedAccountPool,
    private val spend: AccountSpendReader,
    private val clock: Clock,
) : ListSharedAccountsUseCase {
    override suspend fun invoke(caller: Caller): List<SharedAccountView> {
        val now = clock.now()
        val windows = quotas.byAccount(caller.orgId)
        val since = SharedAccountPool.monthStart(now)
        return repository.list(caller.orgId).mapIndexed { index, account ->
            val visible = caller.mayChange(account)
            SharedAccountView(
                account = if (visible) {
                    account
                } else {
                    account.copy(
                        email = null,
                        providerAccountId = "",
                        ownerMemberId = null,
                        label = "",
                    )
                },
                displayLabel = if (visible) account.label else "Account ${index + 1}",
                identityVisible = visible,
                windows = windows[account.id].orEmpty().filter { it.isCurrent(now) }.sortedBy { it.window },
                inFlight = pool.inFlight(account.id),
                spentMicros = spend.spentMicros(account.id, since),
            )
        }
    }
}

/** [UpdateSharedAccountUseCase]. */
class UpdateSharedAccountUseCaseImpl(private val repository: SharedAccountRepository) : UpdateSharedAccountUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid, payload: UpdateAccountPayload): SharedAccount {
        val account = repository.writable(caller, id)
        payload.label?.let(SharingRules::requireLabel)
        val patch = payload.limits
        val limits = if (patch == null) account.limits else patch(account.limits)
        SharingRules.requireLimits(limits)
        payload.priority?.let(SharingRules::requirePriority)
        // RECONNECT_REQUIRED is left only by sharing again: pausing and resuming must not revive dead tokens (the
        // repository also keeps it when the pool retires the account between this read and the write).
        val status = when {
            account.status == AccountStatus.RECONNECT_REQUIRED -> account.status
            payload.disabled == true -> AccountStatus.DISABLED
            payload.disabled == false -> AccountStatus.READY
            else -> account.status
        }
        val updated = account.copy(
            label = payload.label ?: account.label,
            priority = payload.priority ?: account.priority,
            limits = limits,
            status = status,
        )
        return repository.update(updated) ?: throw AccountNotFoundException()
    }
}

/** [RemoveSharedAccountUseCase]. */
class RemoveSharedAccountUseCaseImpl(private val repository: SharedAccountRepository) : RemoveSharedAccountUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid) {
        repository.writable(caller, id)
        if (!repository.delete(caller.orgId, id)) throw AccountNotFoundException()
    }
}

/** The account if the caller may change it (admin or owner); otherwise not found, so existence does not leak. */
/** Admins change every account of their org; a member only the accounts it shared. */
private fun Caller.mayChange(account: SharedAccount) = role.isAdmin || account.ownerMemberId == memberId

private suspend fun SharedAccountRepository.writable(caller: Caller, id: Uuid): SharedAccount {
    val account = get(caller.orgId, id) ?: throw AccountNotFoundException()
    if (!caller.mayChange(account)) throw AccountNotFoundException()
    return account
}
