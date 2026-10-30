// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** The wait does not exist, or the caller may not read it: the two are never told apart. */
class WaitNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Wait not found.")

/**
 * The question of a wait is malformed; nothing is stored.
 *
 * @param message What to fix.
 */
class InvalidWaitException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/**
 * The answer does not fit the wait's fields and actions; nothing is stored.
 *
 * @param message What to fix.
 */
class InvalidWaitAnswerException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** The wait is already answered or cancelled: the first answer won. */
class WaitClosedException : OstromException(ErrorKind.CONFLICT, "This wait is already answered or cancelled.")

/**
 * Too many waits are open for the same session or asker.
 *
 * @param limit The most open at once.
 */
class WaitLimitException(limit: Int) : OstromException(
    ErrorKind.CONFLICT,
    "At most $limit waits can be open at once here: answer or cancel one first.",
)

/**
 * The caller reads the wait but may not do this to it.
 *
 * @param message Who may.
 */
class WaitForbiddenException(message: String) : OstromException(ErrorKind.FORBIDDEN, message)

/** Another request with the same Idempotency-Key was stored and removed meanwhile: send it again. */
class WaitKeyConflictException : OstromException(
    ErrorKind.CONFLICT,
    "Another request used this Idempotency-Key at the same time: send it again.",
)

/** The assignee is not an active member of the organization. */
class InvalidAssigneeException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "The assignee is not an active member of this organization: give a member id.",
)

/**
 * The credential lacks the scope the operation needs.
 *
 * @param scope The scope needed.
 */
class MissingWaitScopeException(scope: String) : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential lacks the $scope scope.",
)

/** The credential acts for no organization. */
class NoOrganizationException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/**
 * Throws an [InvalidWaitException] unless [condition] holds.
 *
 * @param condition What must hold.
 * @param message What to fix otherwise.
 */
internal inline fun requireWait(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidWaitException(message())
}

/**
 * Throws an [InvalidWaitAnswerException] unless [condition] holds.
 *
 * @param condition What must hold.
 * @param message What to fix otherwise.
 */
internal inline fun requireAnswer(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidWaitAnswerException(message())
}
