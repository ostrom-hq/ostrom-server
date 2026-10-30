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

/** The organization already runs as many sessions as its plan allows. */
class CloudSessionLimitException(max: Int) : OstromException(
    ErrorKind.CONFLICT,
    "Your plan runs at most $max cloud sessions at once: pause or delete one first.",
)

/** The organization used all the execution hours of its plan this month. */
class CloudHoursExhaustedException(hours: Int) : OstromException(
    ErrorKind.CONFLICT,
    "Your plan's $hours cloud hours of this month are used up.",
)

/**
 * The session's state does not allow the action.
 *
 * @param message Why.
 */
class CloudSessionStateException(message: String) : OstromException(ErrorKind.CONFLICT, message)

/** The runner's credential is not the current execution's: it must stop and exit. */
class ExecutionFencedException : OstromException(ErrorKind.CONFLICT, "This execution is no longer the session's current one.")

/** The runner's credential is unknown. */
class RunnerUnauthenticatedException : OstromException(ErrorKind.UNAUTHORIZED, "A valid runner token is required.")

/** The caller's credential carries no organization. */
class NoOrganizationException : OstromException(ErrorKind.FORBIDDEN, "This credential is not bound to an organization.")

/** The caller's credential lacks the scope the action needs. */
class MissingScopeException(scope: String) : OstromException(ErrorKind.FORBIDDEN, "This credential lacks the $scope scope.")
