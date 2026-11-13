// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.todos.domain.TodoSession
import run.ostrom.server.todos.domain.TodoSessions
import kotlin.uuid.Uuid

/**
 * [TodoSessions] on the sessions module: the segments of the session one id names (one query), and whether a member
 * is its person, whose login the session keeps (`dev`).
 *
 * @param sessions The sessions module's segments.
 * @param logins The members' logins (the orgs module).
 */
internal class SessionsTodoSessions(
    private val sessions: SessionsRepository,
    private val logins: MemberLogins,
) : TodoSessions {
    override suspend fun find(orgId: Uuid, sessionId: String, memberId: String): TodoSession? {
        val segments = sessions.segmentsOf(orgId, sessionId)
        val first = segments.firstOrNull() ?: return null
        val owner = logins.idsOf(orgId, listOf(first.dev))[first.dev]
        return TodoSession(first.rootId, segments.map { it.id }, owner.toString() == memberId)
    }
}
