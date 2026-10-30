package dev.ostrom.server.runners.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** The session does not exist or belongs to another organization or member. */
class CloudSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Cloud session not found.")

/**
 * The request is malformed.
 *
 * @param message What to fix.
 */
class InvalidCloudSessionRequestException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/**
 * Refuses a malformed request.
 *
 * @param condition What a valid request satisfies.
 * @param message What to fix, built only when the request is refused.
 *
 * @throws InvalidCloudSessionRequestException When [condition] is false.
 */
internal inline fun ensureValid(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidCloudSessionRequestException(message())
}

/** The organization already runs as many sessions as its plan allows. */
class CloudSessionLimitException(max: Int) : OstromException(
    ErrorKind.PAYMENT_REQUIRED,
    "Your plan runs at most $max cloud sessions at once: pause or delete one first.",
)

/** The organization used all the execution hours of its plan this month. */
class CloudHoursExhaustedException(hours: Int) : OstromException(
    ErrorKind.PAYMENT_REQUIRED,
    "Your plan's $hours cloud hours of this month are used up.",
)

/**
 * The session's state does not allow the action.
 *
 * @param message Why.
 */
class CloudSessionStateException(message: String) : OstromException(ErrorKind.CONFLICT, message)

/** The runner's credential is not the current execution's: it must stop and exit. */
class ExecutionFencedException : OstromException(
    ErrorKind.CONFLICT,
    "This execution is no longer the session's current one.",
)

/** The runner's credential is unknown. */
class RunnerUnauthenticatedException : OstromException(ErrorKind.UNAUTHORIZED, "A valid runner token is required.")

/** The caller acts for no organization: answered like an unknown organization, so that none leaks. */
class NotAMemberException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/**
 * The caller's credential lacks the scope the action needs.
 *
 * @param scope The missing scope.
 */
class MissingScopeException(scope: String) : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential lacks the $scope scope.",
)
