// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.presentation.BudgetDto
import dev.ostrom.server.budgets.presentation.InvalidBudgetRequestException
import dev.ostrom.server.budgets.presentation.toLimits
import dev.ostrom.server.budgets.presentation.toTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BudgetsMappersTest {
    @Test
    fun aLimitIsReadInMillionthsOfADollarAndOnlyAsAFiniteNumber() {
        assertEquals(listOf(BudgetLimit(12_345_679L)), BudgetDto("org", usdPerMonth = 12.3456789).toLimits())
        assertEquals(emptyList(), BudgetDto("org").toLimits(), "No amount removes the limits")
        for (usd in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<InvalidBudgetRequestException> { BudgetDto("org", usdPerMonth = usd).toLimits() }
            assertFailsWith<InvalidBudgetRequestException> { BudgetDto("org", usdPerDay = usd).toLimits() }
        }
    }

    @Test
    fun aBudgetIsByTheMonthAndTheDayAndStopsOrWarnsAsAWhole() {
        assertEquals(
            listOf(BudgetLimit(5_000_000), BudgetLimit(1_000_000, BudgetWindow.DAY)),
            BudgetDto("member", usdPerMonth = 5.0, usdPerDay = 1.0).toLimits(),
        )
        assertEquals(
            listOf(BudgetLimit(1_000_000, BudgetWindow.DAY, BudgetAction.WARN)),
            BudgetDto("member", usdPerDay = 1.0, hardStop = false).toLimits(),
        )
    }

    @Test
    fun theScopeIsReadWhateverItsCase() {
        assertEquals(BudgetTarget.Org, BudgetDto("Organization").toTarget())
        assertEquals(BudgetTarget.DefaultMember, BudgetDto("member").toTarget())
    }
}
