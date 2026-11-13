// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The definition of an automation is invalid; nothing is stored.
 *
 * @param message What is wrong, safe to show.
 */
class InvalidAutomationException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** No such automation for the caller (another member's, another organization's, or none). */
class AutomationNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Automation not found.")

/** The caller may read the automation but not do this to it. */
class AutomationForbiddenException(message: String) : OstromException(ErrorKind.FORBIDDEN, message)

/** The definition changed since the caller read it. */
class StaleAutomationException :
    OstromException(ErrorKind.CONFLICT, "The automation changed since you read it: reload it and try again.")

/**
 * The member owns as many automations as allowed.
 *
 * @param limit The limit.
 */
class AutomationLimitException(limit: Int) :
    OstromException(ErrorKind.CONFLICT, "A member owns at most $limit automations.")

/** A webhook request without a valid proof, or for no automation: the answer never tells which. */
class InvalidSignatureException : OstromException(ErrorKind.UNAUTHORIZED, "Invalid webhook signature.")

/** A webhook reached a paused automation (its proof was valid). */
class AutomationDisabledException : OstromException(ErrorKind.CONFLICT, "This automation is paused.")

/** A webhook sender exceeded the per-minute rate, or the automation its runs of the day. */
class AutomationRateLimitException(message: String) : OstromException(ErrorKind.RATE_LIMITED, message)

/** The webhook body is not a JSON object of at most 64 KiB. */
class InvalidWebhookPayloadException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/**
 * The credential lacks the scope the route needs.
 *
 * @param scope The scope.
 */
class MissingAutomationScopeException(scope: String) :
    OstromException(ErrorKind.FORBIDDEN, "This credential lacks the $scope scope.")

/** Checks a definition rule. */
internal inline fun requireAutomation(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidAutomationException(message())
}

/** A webhook delivery already received (S2-50): a retry or a replay, never run twice. */
class WebhookReplayException : OstromException(ErrorKind.CONFLICT, "This delivery was already received.")

/**
 * A webhook URL pinned to a version of the definition reached an automation that is no longer at it; nothing is
 * recorded (TC-AUT-9).
 *
 * @param pinned The version in the URL.
 * @param current The automation's version.
 */
class AutomationVersionChangedException(pinned: Int, current: Int) : OstromException(
    ErrorKind.CONFLICT,
    "This URL is pinned to version $pinned; the automation is at version $current. " +
        "Use the URL of the current version, or the latest one.",
    type = "automation_version_changed",
)

/** No such run for this automation. */
class AutomationRunNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Run not found.")

/** The run is over (finished, failed, skipped or already cancelled): there is nothing to cancel. */
class AutomationRunEndedException :
    OstromException(ErrorKind.CONFLICT, "This run has already ended.", type = "run_ended")
