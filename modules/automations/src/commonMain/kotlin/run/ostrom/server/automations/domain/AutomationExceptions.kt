// SPDX-License-Identifier: AGPL-3.0-only
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
