// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The session has no todo.
 *
 * @return The refusal.
 */
fun todoNotFound(): TodoException = TodoException(ErrorKind.NOT_FOUND, "todo_not_found", "This session has no todo.")

/** The session does not exist, or the caller may not read its todo: the two are never told apart. */
class TodoSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")

/** An administrator reads any session's todo, but only the session's person or their agent changes it. */
class TodoWriteForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "An administrator reads this todo; only the session's person or their agent changes it.",
)

/** The member does not exist in the organization. */
class TodoMemberNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Member not found.")

/** Only an administrator who signed in turns a member's Stop hook on or off, or lists who has it off. */
class TodoAdminRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an administrator of the organization changes the todo Stop hook of a member.",
)

/** A service key, a device or a runner token: a session's todo is its person's and their agent's only. */
class TodoCredentialRefusedException : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential does not reach a session's todo: its person or the session's agent token does.",
)

/** An agent token named no session: it acts only in one of its launch's sessions, which it names. */
class TodoAgentSessionRequiredException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Name the session (sessionId): an agent token acts only in its own session.",
)
