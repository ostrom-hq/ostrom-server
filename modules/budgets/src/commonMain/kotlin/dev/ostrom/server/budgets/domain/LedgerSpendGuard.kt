// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.usage.domain.Admission
import dev.ostrom.server.usage.domain.SpendAdmission
import dev.ostrom.server.usage.domain.SpendHold
import dev.ostrom.server.usage.domain.SpendLimits
import dev.ostrom.server.usage.domain.SpendScope
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The gateway's [SpendGuard] on the limits and the ledger: past a limit, the gateway refuses. [BudgetGuard] answers
 * first, from its cache (a map lookup on the hot path): without a limit, the call goes with no lock and no hold; with a
 * limit reached, it is refused at once; its alerts at 80 % and 100 % go from there. Otherwise the limits are read again
 * and the ledger admits the call against fresh sums under the org's lock ([SpendAdmission]): calls at once never hold
 * more than a limit leaves. A limit set on another replica applies within the guard's cache time (5 s); on this one,
 * at once (the limit's use cases forget the cached decisions).
 *
 * What it does not promise: a provider that bills more than the call's reserve (an input larger than its estimate)
 * can take the spend past a limit, by that gap; the output part of the reserve is bounded by the call's `max_tokens`.
 *
 * @param repository The limits.
 * @param guard The cached decision and its alerts.
 * @param admission The ledger's admission.
 * @param clock The time.
 */
class LedgerSpendGuard(
    private val repository: BudgetsRepository,
    private val guard: BudgetGuard,
    private val admission: SpendAdmission,
    private val clock: Clock,
) : SpendGuard {
    override suspend fun check(request: SpendRequest): SpendDecision {
        val period = BudgetPeriod.of(clock.now())
        val answer = when (val cached = guard.check(request.orgId, request.memberId)) {
            // No limit applied when the guard last looked (its cache, forgotten when a limit changes here).
            BudgetDecision.Allowed(null) -> Admission.Admitted
            is BudgetDecision.Exhausted -> Admission.Exhausted(cached.scope.toSpendScope(), cached.limitMicros)
            else -> {
                val limits = repository.limits(request.orgId, request.memberId)
                admission.admit(
                    SpendHold(request.orgId, request.memberId, request.callId, request.reserveMicros),
                    SpendLimits(limits.orgLimitMicros, limits.memberLimitMicros),
                    period.start,
                )
            }
        }
        return answer.toDecision(period, request.reserveMicros)
    }

    private fun BudgetScope.toSpendScope(): SpendScope = when (this) {
        BudgetScope.ORG -> SpendScope.ORG
        BudgetScope.MEMBER -> SpendScope.MEMBER
    }

    /** The two refusals start with fixed words, so that an agent's CLI tells them apart. */
    private fun Admission.toDecision(period: BudgetPeriod, reserve: Long): SpendDecision = when (this) {
        Admission.Admitted -> SpendDecision.Allowed
        is Admission.Exhausted -> SpendDecision.Exhausted(
            "$EXHAUSTED: ${scope.owner()} limit of ${usd(limitMicros)} is spent; it resets on ${period.end.date()}.",
            period.end,
        )
        is Admission.Insufficient -> SpendDecision.Insufficient(
            "$INSUFFICIENT: ${usd(availableMicros)} is left of ${scope.owner()} ${usd(limitMicros)} monthly limit " +
                "and this call may cost up to ${usd(reserve, up = true)}. Lower its max_tokens, or retry once the " +
                "calls in flight finish.",
        )
    }

    private fun SpendScope.owner(): String = when (this) {
        SpendScope.ORG -> "your organization's"
        SpendScope.MEMBER -> "your"
    }

    /**
     * Dollars to the cent; an amount under a dollar and finer than a cent (a test budget, what is left of one) to the
     * micro. [up] rounds a dropped fraction of a cent up: a call's cost is never shown below what was compared.
     */
    private fun usd(micros: Long, up: Boolean = false): String {
        val shown = if (micros >= MICROS_PER_DOLLAR && up) {
            (micros + MICROS_PER_CENT - 1) / MICROS_PER_CENT * MICROS_PER_CENT
        } else {
            micros
        }
        val dollars = "$" + (shown / MICROS_PER_DOLLAR) + "."
        val fraction = (shown % MICROS_PER_DOLLAR).toString().padStart(MICRO_DIGITS, '0')
        return if (shown >= MICROS_PER_DOLLAR || shown % MICROS_PER_CENT == 0L) {
            dollars + fraction.take(CENT_DIGITS)
        } else {
            dollars + fraction.trimEnd('0')
        }
    }

    private fun Instant.date(): String = toString().substringBefore('T')

    /** The fixed starts of the refusals. */
    companion object {
        /** A limit is spent: nothing more this month. */
        const val EXHAUSTED: String = "Monthly budget reached"

        /** What is left does not cover this call. */
        const val INSUFFICIENT: String = "Budget left too low for this call"
    }
}

private const val MICROS_PER_CENT = 10_000L
private const val MICROS_PER_DOLLAR = 1_000_000L
private const val MICRO_DIGITS = 6
private const val CENT_DIGITS = 2
