// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.presentation.BudgetDto
import dev.ostrom.server.budgets.presentation.InvalidBudgetRequestException
import dev.ostrom.server.budgets.presentation.toLimitMicros
import dev.ostrom.server.budgets.presentation.toTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BudgetsMappersTest {
    @Test
    fun aLimitIsReadInMillionthsOfADollarAndOnlyAsAFiniteNumber() {
        assertEquals(12_345_679L, BudgetDto("org", usdPerMonth = 12.3456789).toLimitMicros())
        assertNull(BudgetDto("org").toLimitMicros(), "No amount removes the limit")
        for (usd in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<InvalidBudgetRequestException> { BudgetDto("org", usdPerMonth = usd).toLimitMicros() }
        }
    }

    @Test
    fun theScopeIsReadWhateverItsCase() {
        assertEquals(BudgetTarget.Org, BudgetDto("Organization").toTarget())
        assertEquals(BudgetTarget.DefaultMember, BudgetDto("member").toTarget())
    }
}
