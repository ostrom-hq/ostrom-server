// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A budget the API cannot take: an unknown scope or subject, an amount not finite. */
class InvalidBudgetRequestException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
