// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.domain.models

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The session does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class SessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")

/** The session is another person's, or another launch's for an agent token. */
class NotYourSessionException : OstromException(ErrorKind.FORBIDDEN, "This session is not yours.")

/**
 * The session ended: nothing more is added to it (CORRECTIFS-4). The message starts with `session closed:`, read by the
 * launchers; a session closed as lost says `launcher lost` in it, and its launcher reopens a segment.
 *
 * @param session The ended segment.
 */
class SessionClosedException(session: Session) : OstromException(
    ErrorKind.CONFLICT,
    "$SESSION_CLOSED ${session.id} ended at ${session.endedAt} (${session.source ?: "ended"}); its receipt is signed " +
        "and nothing more is added to it. Reopen a segment (POST /api/v1/sessions/${session.id}/reopen) and send it " +
        "there.",
) {
    /** The start of the message. */
    companion object {
        /** What the launchers look for. */
        const val SESSION_CLOSED: String = "session closed:"
    }
}

/**
 * Another launch resumed the session: this launch cannot reopen it.
 *
 * @param rootId The agent's session id.
 * @param open The open segment of the other launch.
 */
class SessionResumedElsewhereException(rootId: String, open: String) : OstromException(
    ErrorKind.CONFLICT,
    "session $rootId was resumed by another launch ($open): this launch cannot reopen it",
)

/**
 * A malformed session request.
 *
 * @param reason What is wrong.
 */
class InvalidSessionRequestException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)

/**
 * An activity report with another state, or an `observedAt` that is not a time.
 *
 * @param reason What is wrong.
 */
class InvalidSessionActivityException(reason: String) :
    OstromException(ErrorKind.INVALID_REQUEST, reason, type = "invalid_session_activity")

/**
 * An activity report for a segment that ended: its launcher reports to the segment it reopens.
 *
 * @param session The ended segment.
 */
class SessionEndedException(session: Session) : OstromException(
    ErrorKind.CONFLICT,
    "Session ${session.id} has ended.",
    type = "session_ended",
)
