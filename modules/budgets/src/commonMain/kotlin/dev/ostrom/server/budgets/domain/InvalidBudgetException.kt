// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** A limit is negative or above [BudgetRules.MAX_LIMIT_MICROS]. */
class InvalidBudgetException :
    OstromException(ErrorKind.INVALID_REQUEST, "A limit is 0 to ${BudgetRules.MAX_LIMIT_MICROS} USD millionths.")
