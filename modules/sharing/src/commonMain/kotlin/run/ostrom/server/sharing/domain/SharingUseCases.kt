// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
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
 * @property refreshToken The OAuth refresh token, or empty for a long-lived token that is never renewed (one enrolled
 *   from a secret).
 * @property expiresAt When the access token expires.
 * @property limits Its limits.
 * @property disabled Stores a new account, or one waiting for a reconnection, turned off in the same write: an
 *   account its source had disabled never serves, not even for a moment. Only an import sets it (the share route
 *   never does); re-sharing a serving account never turns it off, and sharing again never turns one back on.
 * @property ifVersion Shares only if the org's account is still at this [SharedAccount.version] (0: only if the org
 *   does not have it), so a reconnection or an edit made since it was read is never overwritten; null shares anyway.
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
    val disabled: Boolean = false,
    val ifVersion: Long? = null,
) {
    override fun toString(): String =
        "ShareAccountPayload(provider=$provider, label=$label, providerAccountId=$providerAccountId, tokens=[REDACTED])"
}

/**
 * Changes an account; null fields stay.
 *
 * @property label New label.
 * @property priority New priority.
 * @property disabled Turns it off or back on (a RECONNECT_REQUIRED or WRONG_ACCOUNT account comes back only by
 *   sharing it again).
 * @property limits The new limits from the current ones, so a change of one limit keeps the others.
 * @property ifVersion Changes it only if it is still at this [SharedAccount.version]; null changes it anyway.
 * @property sharedWith The members who may use it from now on, by login (or member id), after [limits]; empty for
 *   the whole org.
 */
data class UpdateAccountPayload(
    val label: String? = null,
    val priority: Int? = null,
    val disabled: Boolean? = null,
    val limits: ((AccountLimits) -> AccountLimits)? = null,
    val ifVersion: Long? = null,
    val sharedWith: List<String>? = null,
)

/**
 * An account as the caller may see it.
 *
 * @property account The account; identity fields blanked unless [identityVisible].
 * @property displayLabel The label, or "Account N" for members other than its owner.
 * @property identityVisible Whether the caller is an admin or its owner.
 * @property windows Current provider windows.
 * @property inFlight Requests in flight, on every replica.
 * @property spentMicros Equivalent API cost served this month.
 * @property owner The login of the member who shared it ([MemberNames]); empty unless [identityVisible] or when an
 *   admin added it.
 * @property sharedWith The logins of the members it is lent to and the `group:<id>` of its groups, sorted; empty for
 *   the whole org, and unless [identityVisible].
 */
data class SharedAccountView(
    val account: SharedAccount,
    val displayLabel: String,
    val identityVisible: Boolean,
    val windows: List<QuotaWindow>,
    val inFlight: Int,
    val spentMicros: Long,
    val owner: String,
    val sharedWith: List<String>,
)

/** Shares an account. */
interface ShareAccountUseCase {
    /** Shares it; returns the stored account. */
    suspend operator fun invoke(caller: Caller, payload: ShareAccountPayload): SharedAccount

    /** Refuses what [invoke] would refuse, writing nothing (an import's dry run). */
    suspend fun check(caller: Caller, payload: ShareAccountPayload)
}

/** Lists the org's accounts. */
interface ListSharedAccountsUseCase {
    /** Lists them, identities visible to admins and owners only. */
    suspend operator fun invoke(caller: Caller): List<SharedAccountView>
}

/** The view of an account its writer has just shared or changed (an admin or its owner): identity visible. */
interface ViewSharedAccountUseCase {
    /** Names its owner and the members it is lent to. */
    suspend operator fun invoke(caller: Caller, account: SharedAccount): SharedAccountView
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
        val account = sameAccount(caller, payload).sharedAgain(caller, payload, clock)
        val credentials = AccountCredentials(payload.accessToken, payload.refreshToken, payload.expiresAt)
        return repository.upsert(account, cipher.seal(credentials, account.orgId, account.id), payload.ifVersion)
    }

    override suspend fun check(caller: Caller, payload: ShareAccountPayload) {
        sameAccount(caller, payload)
    }

    /** The account [payload] shares again, if the org has it, once every refusal is ruled out. */
    private suspend fun sameAccount(caller: Caller, payload: ShareAccountPayload): SharedAccount? {
        SharingRules.requireLabel(payload.label)
        SharingRules.requireLimits(payload.limits)
        SharingRules.requireTokens(payload.accessToken, payload.refreshToken, payload.providerAccountId)
        SharingRules.requireEmail(payload.email)
        val existing = repository.list(caller.orgId)
        val same = existing.firstOrNull {
            it.provider == payload.provider && it.providerAccountId == payload.providerAccountId
        }
        val expected = payload.ifVersion
        val refusal = when {
            expected != null && (same?.version ?: 0) != expected -> AccountChangedException()
            same == null && existing.size >= SharingRules.MAX_ACCOUNTS -> TooManyAccountsException()
            same != null && !caller.mayChange(same) -> AccountOwnedByAnotherMemberException()
            else -> null
        }
        if (refusal != null) throw refusal
        return same
    }
}

/**
 * The account a share stores: a new one owned by the caller with the requested limits, or [this] with new tokens and
 * label (its owner, priority, limits, creation and a READY or DISABLED status kept: an admin's restrictions survive
 * the owner sharing again after a reconnection; limits change through an update). An account waiting for a
 * reconnection comes back READY, or DISABLED when the payload asks it.
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
        status = if (payload.disabled) AccountStatus.DISABLED else AccountStatus.READY,
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
            status = if (status.awaitsSignIn) fresh.status else status,
            createdAt = createdAt,
        )
    }
}

/** [ListSharedAccountsUseCase]; [names] names the people of the accounts the caller may change. */
class ListSharedAccountsUseCaseImpl(
    private val repository: SharedAccountRepository,
    private val quotas: QuotaWindowRepository,
    private val pool: SharedAccountPool,
    private val spend: AccountSpendReader,
    private val clock: Clock,
    private val names: MemberNames = MemberNames(NoMemberLogins),
) : ListSharedAccountsUseCase {
    override suspend fun invoke(caller: Caller): List<SharedAccountView> {
        val now = clock.now()
        val windows = quotas.byAccount(caller.orgId)
        val since = SharedAccountPool.monthStart(now)
        val accounts = repository.list(caller.orgId)
        val changeable = accounts.filter { caller.mayChange(it) }
        val named = names.of(caller.orgId, changeable)
        val groups = names.groupsOf(caller.orgId, changeable)
        val active = pool.inFlight(caller.orgId)
        return accounts.mapIndexed { index, account ->
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
                inFlight = active[account.id] ?: 0,
                spentMicros = spend.spentMicros(account.id, since),
                owner = if (visible) named.ownerOf(account) else "",
                sharedWith = if (visible) named.sharedWithOf(account, groups) else emptyList(),
            )
        }
    }
}

/** [ViewSharedAccountUseCase]. */
class ViewSharedAccountUseCaseImpl(private val names: MemberNames) : ViewSharedAccountUseCase {
    override suspend fun invoke(caller: Caller, account: SharedAccount): SharedAccountView {
        val named = names.of(caller.orgId, listOf(account))
        val groups = names.groupsOf(caller.orgId, listOf(account))
        return SharedAccountView(
            account = account,
            displayLabel = account.label,
            identityVisible = true,
            windows = emptyList(),
            inFlight = 0,
            spentMicros = 0,
            owner = named.ownerOf(account),
            sharedWith = named.sharedWithOf(account, groups),
        )
    }
}

/** [UpdateSharedAccountUseCase]; [names] reads the members of `sharedWith`. */
class UpdateSharedAccountUseCaseImpl(
    private val repository: SharedAccountRepository,
    private val names: MemberNames = MemberNames(NoMemberLogins),
) : UpdateSharedAccountUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid, payload: UpdateAccountPayload): SharedAccount {
        val account = repository.writable(caller, id)
        payload.label?.let(SharingRules::requireLabel)
        val patch = payload.limits
        val patched = if (patch == null) account.limits else patch(account.limits)
        val sharing = payload.sharedWith?.let { names.sharedWith(caller.orgId, it) }
        val limits = if (sharing == null) patched else patched.copy(allowedMembers = sharing.members)
        SharingRules.requireLimits(limits)
        payload.priority?.let(SharingRules::requirePriority)
        // RECONNECT_REQUIRED and WRONG_ACCOUNT are left only by sharing again: pausing and resuming must not revive
        // dead or foreign tokens (the repository also keeps them when the pool retires the account between this read
        // and the write).
        val status = when {
            account.status.awaitsSignIn -> account.status
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
        val saved = repository.update(updated, payload.ifVersion)
            ?: throw if (payload.ifVersion == null) AccountNotFoundException() else AccountChangedException()
        sharing?.let { names.lend(caller.orgId, saved.id, it) }
        return saved
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
