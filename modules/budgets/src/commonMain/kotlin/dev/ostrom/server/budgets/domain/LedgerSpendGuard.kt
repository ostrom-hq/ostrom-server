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
 * The gateway's [SpendGuard] on the limits and the ledger: past a limit, the gateway refuses. Without a limit, a call
 * goes with one read of the limits, no lock and no hold. With one, [BudgetGuard] answers first (its alerts at 80 % and
 * 100 %, and a quick refusal once a limit is reached, from its cache), then the ledger admits the call against fresh
 * sums under the org's lock ([SpendAdmission]): calls at once never hold more than a limit leaves.
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
        val limits = repository.limits(request.orgId, request.memberId)
        if (limits.orgLimitMicros == null && limits.memberLimitMicros == null) return SpendDecision.Allowed
        val period = BudgetPeriod.of(clock.now())
        val cached = guard.check(request.orgId, request.memberId)
        val answer = if (cached is BudgetDecision.Exhausted) {
            Admission.Exhausted(cached.scope.toSpendScope(), cached.limitMicros)
        } else {
            admission.admit(
                SpendHold(request.orgId, request.memberId, request.callId, request.reserveMicros),
                SpendLimits(limits.orgLimitMicros, limits.memberLimitMicros),
                period.start,
            )
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
                "and this call may cost up to ${usd(reserve)}. Lower its max_tokens, or retry once the calls in " +
                "flight finish.",
        )
    }

    private fun SpendScope.owner(): String = when (this) {
        SpendScope.ORG -> "your organization's"
        SpendScope.MEMBER -> "your"
    }

    /** Dollars to the cent; an amount finer than a cent (a test budget, what is left of one) to the micro. */
    private fun usd(micros: Long): String {
        val dollars = "$" + (micros / MICROS_PER_DOLLAR) + "."
        val fraction = (micros % MICROS_PER_DOLLAR).toString().padStart(MICRO_DIGITS, '0')
        return if (micros >= MICROS_PER_DOLLAR || micros % MICROS_PER_CENT == 0L) {
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
