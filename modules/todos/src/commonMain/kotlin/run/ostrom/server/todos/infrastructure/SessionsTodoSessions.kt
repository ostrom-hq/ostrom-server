// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.sessions.CloudAgentSessions
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.todos.domain.TodoSession
import run.ostrom.server.todos.domain.TodoSessions
import kotlin.uuid.Uuid

/**
 * [TodoSessions] on the sessions module: the segments of the session one id names (one query), and whether a member
 * is its person, whose login the session keeps (`dev`). An id no segment has may be a cloud session's (the runners
 * module's [CloudAgentSessions]), whose agent keeps its todo there whatever execution runs it: the cloud session's id
 * keys it.
 *
 * @param sessions The sessions module's segments.
 * @param logins The members' logins (the orgs module).
 * @param cloud The cloud sessions, when the runners module is there.
 */
internal class SessionsTodoSessions(
    private val sessions: SessionsRepository,
    private val logins: MemberLogins,
    private val cloud: CloudAgentSessions? = null,
) : TodoSessions {
    override suspend fun find(orgId: Uuid, sessionId: String, memberId: String): TodoSession? {
        val segments = sessions.segmentsOf(orgId, sessionId)
        val first = segments.firstOrNull() ?: return cloudSession(orgId, sessionId, memberId)
        return TodoSession(first.rootId, segments.map { it.id }, ownedBy(orgId, first.dev, memberId))
    }

    private suspend fun cloudSession(orgId: Uuid, sessionId: String, memberId: String): TodoSession? {
        val session = cloud?.find(orgId, sessionId) ?: return null
        return TodoSession(session.segments.first(), session.segments, ownedBy(orgId, session.owner, memberId))
    }

    private suspend fun ownedBy(orgId: Uuid, login: String, memberId: String): Boolean =
        logins.idsOf(orgId, listOf(login))[login].toString() == memberId
}
