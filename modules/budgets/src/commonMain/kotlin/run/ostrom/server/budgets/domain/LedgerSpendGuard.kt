// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.SpendRequest
import run.ostrom.server.usage.domain.Admission
import run.ostrom.server.usage.domain.DailySpendLimits
import run.ostrom.server.usage.domain.SpendAdmission
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.SpendScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The gateway's [SpendGuard] on the limits and the ledger: past a limit that stops, the gateway refuses; a limit that
 * warns never refuses ([BudgetGuard] measures it for its alerts and audit line). [BudgetGuard] answers first, from
 * the limits and the spend as they are now: without a limit that stops, the call goes with no lock and no hold; with
 * one reached, it is refused at once; its alerts at 80 % and 100 % go from there. Otherwise the limits are read again
 * and the ledger admits the call against fresh sums of the month and of the day under the org's lock
 * ([SpendAdmission]): calls at once never hold more than a limit leaves. Nothing is cached in the process, so a limit
 * set on any replica applies to the next call on every one.
 *
 * What it does not promise: a provider that bills more than the call's reserve (an input larger than its estimate)
 * can take the spend past a limit, by that gap; the output part of the reserve is bounded by the call's `max_tokens`.
 *
 * @param repository The limits.
 * @param guard The decision and its alerts.
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
        val now = clock.now()
        val month = BudgetPeriod.of(now)
        val day = BudgetPeriod.day(now)
        val answer = when (val decided = guard.check(request.orgId, request.memberId, request.workflowId)) {
            // No limit that stops applies.
            BudgetDecision.Allowed(null) -> Admission.Admitted
            is BudgetDecision.Exhausted ->
                Admission.Exhausted(decided.scope.toSpendScope(), decided.limitMicros, decided.window == BudgetWindow.DAY)
            else -> admission.admit(
                SpendHold(request.orgId, request.memberId, request.callId, request.reserveMicros, request.workflowId),
                repository.limits(request.orgId, request.memberId, request.workflowId).stopping(day.start),
                month.start,
            )
        }
        return answer.toDecision(month, day, request.reserveMicros)
    }

    override suspend fun release(orgId: Uuid, callId: Uuid) = admission.release(orgId, callId)

    /** The limits that stop, for the ledger: those that warn never refuse a call. */
    private fun BudgetLimits.stopping(dayStart: Instant): SpendLimits {
        val orgDay = org.stopping(BudgetWindow.DAY)
        val memberDay = member.stopping(BudgetWindow.DAY)
        val workflowDay = workflow.stopping(BudgetWindow.DAY)
        val daily = DailySpendLimits(dayStart, orgDay, memberDay, workflowDay)
            .takeUnless { orgDay == null && memberDay == null && workflowDay == null }
        return SpendLimits(
            org.stopping(BudgetWindow.MONTH),
            member.stopping(BudgetWindow.MONTH),
            daily,
            workflow.stopping(BudgetWindow.MONTH),
        )
    }

    private fun List<BudgetLimit>.stopping(window: BudgetWindow): Long? =
        firstOrNull { it.window == window && it.action == BudgetAction.BLOCK }?.micros

    private fun BudgetScope.toSpendScope(): SpendScope = when (this) {
        BudgetScope.ORG -> SpendScope.ORG
        BudgetScope.MEMBER -> SpendScope.MEMBER
        BudgetScope.WORKFLOW -> SpendScope.WORKFLOW
    }

    /** The refusals start with fixed words, so that an agent's CLI tells them apart. */
    private fun Admission.toDecision(month: BudgetPeriod, day: BudgetPeriod, reserve: Long): SpendDecision =
        when (this) {
            Admission.Admitted -> SpendDecision.Allowed
            is Admission.Exhausted -> if (daily) {
                SpendDecision.Exhausted(
                    "$EXHAUSTED_TODAY: ${scope.owner()} limit of ${usd(limitMicros)} a day is spent; it resets on " +
                        "${day.end.date()}.",
                    day.end,
                )
            } else {
                SpendDecision.Exhausted(
                    "$EXHAUSTED: ${scope.owner()} limit of ${usd(limitMicros)} is spent; it resets on " +
                        "${month.end.date()}.",
                    month.end,
                )
            }
            is Admission.Insufficient -> {
                val per = if (daily) "daily" else "monthly"
                SpendDecision.Insufficient(
                    "$INSUFFICIENT: ${usd(availableMicros)} is left of ${scope.owner()} ${usd(limitMicros)} $per " +
                        "limit and this call may cost up to ${usd(reserve, up = true)}. Lower its max_tokens, or " +
                        "retry once the calls in flight finish.",
                )
            }
        }

    private fun SpendScope.owner(): String = when (this) {
        SpendScope.ORG -> "your organization's"
        SpendScope.MEMBER -> "your"
        SpendScope.WORKFLOW -> "this workflow's"
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
        /** A monthly limit is spent: nothing more this month. */
        const val EXHAUSTED: String = "Monthly budget reached"

        /** A daily limit is spent: nothing more today. */
        const val EXHAUSTED_TODAY: String = "Daily budget reached"

        /** What is left does not cover this call. */
        const val INSUFFICIENT: String = "Budget left too low for this call"
    }
}

private const val MICROS_PER_CENT = 10_000L
private const val MICROS_PER_DOLLAR = 1_000_000L
private const val MICRO_DIGITS = 6
private const val CENT_DIGITS = 2
