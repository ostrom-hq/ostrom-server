// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetOverview
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.MyBudget
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

private const val MICROS_PER_USD = 1_000_000.0
private const val ORGANIZATION = "organization"
private const val MEMBER = "member"

/** Which subject the request sets. */
fun BudgetDto.toTarget(): BudgetTarget {
    val member = subject
    return when (scope.lowercase()) {
        ORGANIZATION, "org" -> BudgetTarget.Org
        MEMBER -> if (member == null) BudgetTarget.DefaultMember else BudgetTarget.Member(member.toMemberId())
        else -> throw InvalidBudgetRequestException("The scope of a budget is organization or member.")
    }
}

private fun String.toMemberId(): Uuid =
    Uuid.parseOrNull(this) ?: throw InvalidBudgetRequestException("The subject of a member budget is a member id.")

/**
 * The subject's limits: a monthly one for [BudgetDto.usdPerMonth], a daily one for [BudgetDto.usdPerDay], both
 * stopping the calls with [BudgetDto.hardStop] or both only warning without; none removes the subject's limits.
 */
fun BudgetDto.toLimits(): List<BudgetLimit> {
    val action = if (hardStop) BudgetAction.BLOCK else BudgetAction.WARN
    return listOfNotNull(
        usdPerMonth?.let { BudgetLimit(it.toMicros("usdPerMonth"), BudgetWindow.MONTH, action) },
        usdPerDay?.let { BudgetLimit(it.toMicros("usdPerDay"), BudgetWindow.DAY, action) },
    )
}

private fun Double.toMicros(field: String): Long {
    if (!isFinite()) throw InvalidBudgetRequestException("$field is a number of US dollars.")
    return (this * MICROS_PER_USD).roundToLong()
}

/** The budget as stored, scope spelled as the API lists it. */
fun BudgetDto.normalized(): BudgetDto = copy(scope = if (toTarget() == BudgetTarget.Org) ORGANIZATION else MEMBER)

/** Maps an org's budgets to the API: one budget per subject, with its monthly and daily amounts. */
fun BudgetOverview.toPage(): BudgetsPage {
    val shared = listOfNotNull(
        settings.org.takeIf { it.isNotEmpty() }?.toDto(ORGANIZATION, null),
        settings.defaultMember.takeIf { it.isNotEmpty() }?.toDto(MEMBER, null),
    )
    val members = settings.overrides.groupBy { it.memberId }
        .map { (memberId, rows) -> rows.map { it.limit }.toDto(MEMBER, memberId.toString()) }
    return BudgetsPage(
        items = shared + members,
        orgSpentUsd = orgSpentMicros.toUsd(),
        periodStart = period.start.toString(),
        resetsAt = period.end.toString(),
    )
}

/** A subject's limits, at least one, as one budget; it stops if they all do. */
private fun List<BudgetLimit>.toDto(scope: String, subject: String?) = BudgetDto(
    scope = scope,
    subject = subject,
    usdPerMonth = firstOrNull { it.window == BudgetWindow.MONTH }?.micros?.toUsd(),
    usdPerDay = firstOrNull { it.window == BudgetWindow.DAY }?.micros?.toUsd(),
    hardStop = all { it.action == BudgetAction.BLOCK },
)

/** Maps the caller's budget to the API. */
fun MyBudget.toResponse() = MyBudgetResponse(
    limitMicros = limitMicros,
    spentMicros = spentMicros,
    remainingMicros = remainingMicros,
    resetsAt = resetsAt.toString(),
    exhausted = decision is BudgetDecision.Exhausted,
    exhaustedScope = (decision as? BudgetDecision.Exhausted)?.let { it.scope.name.lowercase() },
)

private fun Long.toUsd(): Double = this / MICROS_PER_USD
