// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.presentation

import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requireWrite
import run.ostrom.server.todos.domain.TodoAgentSessionRequiredException
import run.ostrom.server.todos.domain.TodoCaller
import run.ostrom.server.todos.domain.TodoCredentialRefusedException

/**
 * Who writes a session's todo besides a browser session (S2-52, declared): the session's agent token, and a person's
 * own token (the CLI's Stop hook of a session not launched by `ostrom`). A service key, a device or a runner never
 * does.
 */
val TODO_WRITERS: WriteAccess = WriteAccess.tokens(PrincipalKind.USER, PrincipalKind.AGENT)

/**
 * Who a todo request acts for. An agent token is the person who launched it, confined to the session it names, which
 * must be one of its launch's (the open segment is found); a person is the member the orgs module resolves, an
 * administrator if their role says so. Any other credential is refused.
 *
 * @param orgs The member a person's credential acts for.
 * @param agents The person and session of an agent token.
 */
class TodoCallers(private val orgs: PrincipalOrgResolver, private val agents: WaitAgentResolver) {
    /**
     * The caller of a read.
     *
     * @param sessionId The session the request names.
     * @return The caller.
     * @throws TodoCredentialRefusedException For a service key, a device or a runner.
     */
    suspend fun reader(principal: AuthenticatedPrincipal, sessionId: String?): TodoCaller =
        callerOf(principal, sessionId)

    /**
     * The caller of a change: a browser session, a person's token or the session's agent token ([TODO_WRITERS]).
     *
     * @param sessionId The session the request names.
     * @return The caller.
     */
    suspend fun writer(principal: AuthenticatedPrincipal, sessionId: String?): TodoCaller {
        val caller = callerOf(principal, sessionId)
        principal.requireWrite(TODO_WRITERS)
        return caller
    }

    /**
     * The caller of the Stop hook switch: a browser session only; whether it administers is the use case's to say.
     *
     * @return The caller.
     */
    suspend fun console(principal: AuthenticatedPrincipal): TodoCaller {
        principal.requireWrite(WriteAccess.BROWSER)
        return callerOf(principal, null)
    }

    private suspend fun callerOf(principal: AuthenticatedPrincipal, sessionId: String?): TodoCaller =
        when (principal.kind) {
            PrincipalKind.AGENT -> {
                val named = sessionId ?: throw TodoAgentSessionRequiredException()
                val agent = agents.resolve(principal, named) ?: throw principal.outsideOrganization()
                TodoCaller(agent.orgId, agent.memberId, admin = false, agentSession = agent.sessionId)
            }
            PrincipalKind.USER -> {
                val member = orgs.resolve(principal, null) ?: throw principal.outsideOrganization()
                TodoCaller(member.orgId, member.memberId.toString(), admin = member.role in ADMIN_ROLES)
            }
            PrincipalKind.SERVICE, PrincipalKind.DEVICE -> throw TodoCredentialRefusedException()
        }

    private companion object {
        val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)
    }
}
