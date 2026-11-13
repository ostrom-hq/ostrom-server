// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A workflow budget names a workflow the org does not have: answered like an unknown workflow, whether another org has
 * it or not.
 */
class BudgetWorkflowNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Workflow not found.")

/** A limit is negative or above [BudgetRules.MAX_LIMIT_MICROS]. */
class InvalidBudgetException :
    OstromException(ErrorKind.INVALID_REQUEST, "A limit is 0 to ${BudgetRules.MAX_LIMIT_MICROS} USD millionths.")
