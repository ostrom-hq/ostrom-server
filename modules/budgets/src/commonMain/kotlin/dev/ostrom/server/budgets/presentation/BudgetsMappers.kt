// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetOverview
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.MyBudget
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

private const val MICROS_PER_USD = 1_000_000.0
private const val ORGANIZATION = "organization"
private const val MEMBER = "member"

/** Which limit the request sets. */
fun BudgetDto.toTarget(): BudgetTarget {
    requireSupported()
    val member = subject
    return when (scope.lowercase()) {
        ORGANIZATION, "org" -> BudgetTarget.Org
        MEMBER -> if (member == null) BudgetTarget.DefaultMember else BudgetTarget.Member(member.toMemberId())
        else -> throw InvalidBudgetRequestException("The scope of a budget is organization or member.")
    }
}

private fun BudgetDto.requireSupported() {
    val problem = when {
        usdPerDay != null -> "Daily budgets are not supported: use usdPerMonth."
        !hardStop -> "A budget always stops model calls when reached."
        else -> return
    }
    throw InvalidBudgetRequestException(problem)
}

private fun String.toMemberId(): Uuid =
    Uuid.parseOrNull(this) ?: throw InvalidBudgetRequestException("The subject of a member budget is a member id.")

/** The limit in USD millionths, or null to remove it. */
fun BudgetDto.toLimitMicros(): Long? = usdPerMonth?.let { usd ->
    if (!usd.isFinite()) throw InvalidBudgetRequestException("usdPerMonth is a number of US dollars.")
    (usd * MICROS_PER_USD).roundToLong()
}

/** The budget as stored, scope spelled as the API lists it. */
fun BudgetDto.normalized(): BudgetDto = copy(scope = if (toTarget() == BudgetTarget.Org) ORGANIZATION else MEMBER)

/** Maps an org's budgets to the API. */
fun BudgetOverview.toPage(): BudgetsPage {
    val shared = listOfNotNull(
        settings.orgLimitMicros?.let { BudgetDto(ORGANIZATION, usdPerMonth = it.toUsd()) },
        settings.defaultMemberLimitMicros?.let { BudgetDto(MEMBER, usdPerMonth = it.toUsd()) },
    )
    val members = settings.overrides.map { BudgetDto(MEMBER, it.memberId.toString(), it.limitMicros.toUsd()) }
    return BudgetsPage(
        items = shared + members,
        orgSpentUsd = orgSpentMicros.toUsd(),
        periodStart = period.start.toString(),
        resetsAt = period.end.toString(),
    )
}

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
