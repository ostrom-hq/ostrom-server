// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.AgentSessions
import kotlin.uuid.Uuid

/** Whose the agent sessions are, and who a username names, for the waits. */
interface WaitSessions {
    /**
     * The session [sessionId] names, when [caller] runs it.
     *
     * @param caller The member.
     * @param sessionId Any segment of the session.
     * @param refusal What to throw when someone else runs it.
     *
     * @return The session.
     *
     * @throws WaitSessionNotFoundException When the organization has no such session.
     */
    suspend fun owned(caller: WaitCaller, sessionId: String, refusal: () -> OstromException): AgentSession

    /**
     * The member a username names.
     *
     * @param orgId The organization.
     * @param username The username, any case.
     *
     * @return The member's id, or null when no member of [orgId] has it.
     */
    suspend fun memberNamed(orgId: Uuid, username: String): String?

    /**
     * The username of a member.
     *
     * @param orgId The organization.
     * @param memberId The member's id.
     *
     * @return The username, or null when [memberId] names no member of [orgId].
     */
    suspend fun usernameOf(orgId: Uuid, memberId: String): String?
}

/**
 * [WaitSessions] on the sessions module's [AgentSessions] and the orgs module's [MemberLogins]: a session is its
 * person's, named by their login.
 *
 * @param sessions The agent sessions.
 * @param logins The members' logins.
 */
class WaitSessionsImpl(private val sessions: AgentSessions, private val logins: MemberLogins) : WaitSessions {
    override suspend fun owned(caller: WaitCaller, sessionId: String, refusal: () -> OstromException): AgentSession {
        val session = sessions.find(caller.orgId, sessionId) ?: throw WaitSessionNotFoundException()
        val login = usernameOf(caller.orgId, caller.memberId)
        if (login == null || !login.equals(session.owner, ignoreCase = true)) throw refusal()
        return session
    }

    override suspend fun memberNamed(orgId: Uuid, username: String): String? =
        logins.idsOf(orgId, listOf(username))[username]?.toString()

    override suspend fun usernameOf(orgId: Uuid, memberId: String): String? {
        val id = Uuid.parseOrNull(memberId) ?: return null
        return logins.loginsOf(orgId, listOf(id))[id]
    }
}
