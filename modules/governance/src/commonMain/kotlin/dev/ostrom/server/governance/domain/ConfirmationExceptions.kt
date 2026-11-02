// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** No such confirmation in the caller's organization, or not one they may read: 404, so its existence does not leak. */
class ConfirmationNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Confirmation not found.")

/** Only an owner or an admin asks for a governance change, or answers one (403). */
class ConfirmationManagersOnlyException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization changes its governance.",
)

/** The person already has [ConfirmationRules.MAX_WAITING] confirmations waiting (429). */
class ConfirmationLimitException : OstromException(
    ErrorKind.RATE_LIMITED,
    "You already have ${ConfirmationRules.MAX_WAITING} governance changes waiting for a confirmation: answer or let " +
        "them expire first.",
    "confirmation_limit",
)

/** The confirmation was already answered: it is used once (409). */
class ConfirmationClosedException : OstromException(
    ErrorKind.CONFLICT,
    "This confirmation was already answered.",
    "confirmation_closed",
)

/** The confirmation expired unanswered (410). */
class ConfirmationExpiredException : OstromException(
    ErrorKind.GONE,
    "This confirmation expired: run the command again.",
    "confirmation_expired",
)

/** What the change replaces is no longer what it showed: nothing was written (409). */
class ConfirmationStaleException : OstromException(
    ErrorKind.CONFLICT,
    "What this change replaces changed since it was asked: nothing was written. Run the command again.",
    "confirmation_stale",
)

/** A confirmation needs a sign-in less than [ConfirmationRules.STEP_UP_WITHIN] old, as a yes does (S2-54, 403). */
class ConfirmationStepUpRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Sign in again to confirm a governance change: your sign-in is more than 5 minutes old.",
    "step_up_required",
)
