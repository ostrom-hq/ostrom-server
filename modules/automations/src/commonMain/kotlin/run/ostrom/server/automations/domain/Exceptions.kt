// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

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
    "The assignee is not an active member of this organization: give a member id or a username.",
)

/** A member asks a wait in a session that is not theirs (another member's, an administrator's included). */
class WaitSessionOwnerOnlyException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only the person who runs this session asks a wait in it.",
    "wait_session_owner_only",
)

/** The session ended: it opens no new wait. */
class SessionNotRunningException : OstromException(
    ErrorKind.CONFLICT,
    "This session has ended: it opens no new wait.",
    "session_not_running",
)

/** The session named is not one of the organization's. */
class WaitSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")

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

/** An agent token always runs in a session, and acts only inside it (audit A12): it names it with `?session=`. */
class AgentSessionRequiredException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "An agent token names the session it runs in: add ?session=<session id>.",
)

/** An agent token's waits belong to its session: it never names a workflow run. */
class AgentRunRefusedException : OstromException(ErrorKind.FORBIDDEN, "An agent token does not name a run.")

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

/** A wait for a time or an event: nobody answers it, its asker cancels it. */
class WaitResolvesByItselfException : OstromException(
    ErrorKind.CONFLICT,
    "This wait waits for a time or an event, not for a person: it cannot be answered. Cancel it instead.",
    "resolves_by_itself",
)

/**
 * An event address called too often.
 *
 * @param perMinute The most calls a minute.
 */
class WaitEventRateLimitException(perMinute: Int) : OstromException(
    ErrorKind.RATE_LIMITED,
    "This address receives at most $perMinute calls a minute.",
    "wait_event_rate_limit",
)

/** The body of an event is larger than an event wait takes. */
class WaitEventTooLargeException : OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The body is at most 16 KiB.")
