// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.CloudAgentSessions
import run.ostrom.server.runners.domain.CloudSessionsRepository
import kotlin.uuid.Uuid

/**
 * [CloudAgentSessions] on the cloud sessions: one read of the session, one of its owner's login. It is open while an
 * execution of it holds a pod (creating, running, pausing or resuming), as long as its agent may ask; its id is its one
 * segment, which every execution shares.
 *
 * @param sessions The cloud sessions.
 * @param logins The members' logins, when the orgs module is there: without it, no owner is known and no cloud session
 *   is found.
 */
internal class RunnersCloudAgentSessions(
    private val sessions: CloudSessionsRepository,
    private val logins: MemberLogins?,
) : CloudAgentSessions {
    override suspend fun find(orgId: Uuid, sessionId: String): AgentSession? {
        val session = Uuid.parseOrNull(sessionId)?.let { sessions.get(orgId, it) } ?: return null
        val login = Uuid.parseOrNull(session.ownerId)?.let { logins?.loginsOf(orgId, listOf(it))?.get(it) }
        return login?.let { AgentSession(it, session.state.holdsExecution, listOf(session.id.toString())) }
    }
}
