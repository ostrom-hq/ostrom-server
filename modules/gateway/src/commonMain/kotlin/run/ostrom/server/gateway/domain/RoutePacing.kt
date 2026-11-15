// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.sharing.domain.AccountProvider
import kotlin.time.Instant

/**
 * Capacity is specific to the provider actually serving a scope's work: its accounts (TeamCode's `RoutePacingUseCase`,
 * where Ostrom's targets are the subscription providers its sessions' accounts belong to).
 *
 * @param pacing The pacing of one provider.
 */
class RoutePacingUseCase(private val pacing: PacingUseCase = PacingUseCase()) {
    /**
     * The scope's advice: the strictest of its providers'.
     *
     * @param targets The providers the scope's recent calls used.
     * @param accounts Every account the scope may use; each provider is paced on all of its accounts together,
     *   whatever their priority: placement moves work to another account before one runs out.
     * @param maximum The member's concurrency limit, 0 for none.
     * @param now The time.
     * @param allowanceAvailable Whether the member's budgets still let a call through.
     */
    fun advise(
        targets: Set<AccountProvider>,
        accounts: List<AccountPacing>,
        maximum: Int,
        now: Instant,
        allowanceAvailable: Boolean = true,
    ): PacingAdvice {
        if (!allowanceAvailable) return PacingAdvice(Pace.PAUSE, maximum, PacingUseCase.PAUSE_INTERVAL, ALLOWANCE)
        if (targets.isEmpty()) return pacing.advise(emptyList(), maximum, now)
        // One scope may use several providers: never advise acceleration merely because one is idle while another the
        // same scope uses is pressured.
        val advice = targets.map { target ->
            pacing.advise(accounts.filter { it.account.provider == target }, maximum, now)
        }
        val ceiling = concurrencyCeiling(advice.map { it.maxConcurrent } + maximum)
        return pacing.withCeiling(advice.maxWith(PACING_STRICTNESS), ceiling)
    }

    private companion object {
        const val ALLOWANCE = "An applicable member or organization budget is unavailable, or admission is paused. " +
            "Pause new work until capacity returns or access is reviewed."
    }
}
