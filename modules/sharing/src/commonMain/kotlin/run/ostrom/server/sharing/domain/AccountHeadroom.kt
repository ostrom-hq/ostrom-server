// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.time.Instant

/**
 * What is left of one current provider window before its account's `utilizationCeiling`, until [resetsAt] (null when
 * the provider did not say). TeamCode's `WindowHeadroom`.
 *
 * @property window The window's name.
 * @property utilization Its used share, 0..1.
 * @property remainingShare A share of the whole window (0..1): the ceiling minus [utilization], never negative.
 * @property observedAt When it was read.
 * @property resetsAt When it resets, if known.
 */
data class WindowHeadroom(
    val window: String,
    val utilization: Double,
    val remainingShare: Double,
    val observedAt: Instant,
    val resetsAt: Instant?,
)

/**
 * One account that can take a request now (or one that only lacks quota), with the remaining share of each current
 * window. A window absent from [windows] is unknown (never observed, or its reading is stale), not full.
 *
 * @property account The account.
 * @property windows Its current windows, by name.
 */
data class AccountHeadroom(val account: SharedAccount, val windows: List<WindowHeadroom>) {
    /** The window named [name], or null while it is unknown. */
    fun window(name: String): WindowHeadroom? = windows.firstOrNull { it.window == name }

    /** The share at which the account stops taking requests: its `utilizationCeiling`. */
    val ceiling: Double get() = account.limits.utilizationCeiling
}

/**
 * What the capacity computations read of one account: the account (status, ownership, cool-down, ceiling) and its
 * observed windows. TeamCode's `PoolAccountObservation`.
 *
 * @property account The account.
 * @property windows Its last readings.
 */
data class PoolAccountObservation(val account: SharedAccount, val windows: List<QuotaWindow>)

/** Why an account cannot serve now (TeamCode's `AccountUnavailability`). */
enum class AccountUnavailability {
    /** Paused by an admin or its owner. */
    DISABLED,

    /** Imported, waiting for its refresh ownership's handover. */
    PENDING_HANDOVER,

    /** Its tokens no longer serve: the owner shares it again. */
    RECONNECT_REQUIRED,

    /** Resting after a refusal of the provider. */
    COOLING_DOWN,

    /** A current window is at the account's ceiling. */
    QUOTA_EXHAUSTED,
    ;

    /** The classification. */
    companion object {
        /**
         * The one classification of an account that cannot serve, or null when it can.
         *
         * @param account The account.
         * @param quotaExhausted Whether a current window that applies is at its ceiling.
         * @param now The time.
         */
        fun of(account: SharedAccount, quotaExhausted: Boolean, now: Instant): AccountUnavailability? = when {
            account.status == AccountStatus.DISABLED -> DISABLED
            account.ownership == RefreshOwnership.PENDING_HANDOVER -> PENDING_HANDOVER
            account.status != AccountStatus.READY -> RECONNECT_REQUIRED
            account.cooldownUntil?.let { it > now } == true -> COOLING_DOWN
            quotaExhausted -> QUOTA_EXHAUSTED
            else -> null
        }
    }
}

/**
 * The one computation of the remaining capacity of the accounts, per window until its reset, for pacing projections and
 * pool sizing alike (TeamCode's `AccountHeadroomUseCase`). Usable means what the pool means: ready, not pending
 * handover, not cooling down, and no current window at or above the account's ceiling. Excluded accounts are absent
 * from the result, so an exhausted or disconnected account never counts as headroom. Only shared windows apply: the
 * pool never places on a `model:` window.
 */
class AccountHeadroomUseCase {
    /**
     * The accounts that may take a request now.
     *
     * @param accounts The accounts and their readings.
     * @param now The time.
     */
    fun usable(accounts: List<PoolAccountObservation>, now: Instant): List<AccountHeadroom> =
        classify(accounts, now).filter { it.second == null }.map { it.first }

    /**
     * The accounts that only lack quota ([AccountUnavailability.QUOTA_EXHAUSTED]): they come back when their windows at
     * the ceiling reset. Each lists every current window, those at the ceiling with no remaining share.
     *
     * @param accounts The accounts and their readings.
     * @param now The time.
     */
    fun exhausted(accounts: List<PoolAccountObservation>, now: Instant): List<AccountHeadroom> =
        classify(accounts, now).filter { it.second == AccountUnavailability.QUOTA_EXHAUSTED }.map { it.first }

    private fun classify(
        accounts: List<PoolAccountObservation>,
        now: Instant,
    ): List<Pair<AccountHeadroom, AccountUnavailability?>> = accounts.map { observed ->
        val account = observed.account
        val ceiling = account.limits.utilizationCeiling
        val applicable = observed.windows.filter {
            it.observedAt <= now && it.isCurrent(now) && !it.window.startsWith(MODEL_WINDOW)
        }
        val exhausted = applicable.any { it.utilization >= ceiling }
        val headroom = AccountHeadroom(
            account,
            applicable.sortedBy { it.window }.map {
                WindowHeadroom(
                    it.window,
                    it.utilization,
                    (ceiling - it.utilization).coerceIn(0.0, 1.0),
                    it.observedAt,
                    it.resetsAt,
                )
            },
        )
        headroom to AccountUnavailability.of(account, exhausted, now)
    }

    private companion object {
        const val MODEL_WINDOW = "model:"
    }
}
