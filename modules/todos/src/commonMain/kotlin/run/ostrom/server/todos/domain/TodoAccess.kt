// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import kotlin.uuid.Uuid

/**
 * Who calls the todo routes.
 *
 * @property orgId The organization.
 * @property memberId The member: a person, or the person who launched an agent token.
 * @property admin Whether the caller administers the organization (a person, never an agent): it reads any
 *   session's todo.
 * @property agentSession For an agent token, the open segment of its launch's session it is confined to, whatever
 *   the request names; null for anyone else.
 */
data class TodoCaller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean,
    val agentSession: String? = null,
)

/**
 * The session a request acts on.
 *
 * @property orgId The organization.
 * @property sessionId The session's root id, which keys its todo.
 */
data class TodoTarget(val orgId: Uuid, val sessionId: String)

/**
 * The session a request changes, with its person.
 *
 * @property target The session.
 * @property owner Its person: the caller, or the person who launched the agent token that calls.
 */
data class TodoWriter(val target: TodoTarget, val owner: TodoOwner)

/**
 * Finds the session a caller acts on: an agent token its own session, whatever it names; the session's person the
 * session they name; an administrator any session, to read only (a change is forbidden). Anyone else learns
 * nothing: not found.
 *
 * @param sessions The sessions module.
 */
class TodoAccess(private val sessions: TodoSessions) {
    /**
     * The session a caller reads.
     *
     * @param sessionId The session the request names (any of its segments).
     * @return The target.
     * @throws TodoSessionNotFoundException When the caller may not read it, or it does not exist.
     * @throws TodoException `invalid_request` when a person names no session.
     */
    suspend fun reader(caller: TodoCaller, sessionId: String?): TodoTarget {
        val (session, owned) = resolve(caller, sessionId)
        if (!owned && !caller.admin) throw TodoSessionNotFoundException()
        return TodoTarget(caller.orgId, session.rootId)
    }

    /**
     * The session a caller changes: only its person, or their agent token.
     *
     * @param sessionId The session the request names (any of its segments).
     * @return The target and its person.
     * @throws TodoSessionNotFoundException When the caller may not change it, or it does not exist.
     * @throws TodoWriteForbiddenException When an administrator who is not its person tries.
     * @throws TodoException `invalid_request` when a person names no session.
     */
    suspend fun writer(caller: TodoCaller, sessionId: String?): TodoWriter {
        val (session, owned) = resolve(caller, sessionId)
        // An administrator sees the session, so it is told it may not change it; anyone else learns nothing.
        if (!owned) throw if (caller.admin) TodoWriteForbiddenException() else TodoSessionNotFoundException()
        val segment = caller.agentSession ?: session.segments.last()
        val owner = TodoOwner(caller.orgId, caller.memberId, segment, session.segments)
        return TodoWriter(TodoTarget(caller.orgId, session.rootId), owner)
    }

    private suspend fun resolve(caller: TodoCaller, sessionId: String?): Pair<TodoSession, Boolean> {
        val named = caller.agentSession ?: sessionId
            ?: throw invalidTodo("invalid_request", "Name the session (sessionId).")
        val session = sessions.find(caller.orgId, named, caller.memberId) ?: throw TodoSessionNotFoundException()
        return session to (session.owned || caller.agentSession != null)
    }
}
