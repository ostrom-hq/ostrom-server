// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** A budget the API cannot take: a daily limit, no hard stop, an unknown scope or subject, an amount not finite. */
class InvalidBudgetRequestException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
