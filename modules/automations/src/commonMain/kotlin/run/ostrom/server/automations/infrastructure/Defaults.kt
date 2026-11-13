// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.LaunchRequest
import run.ostrom.server.automations.domain.LaunchResult
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.AgentSessions
import kotlin.uuid.Uuid

/**
 * The member directory used until the module owning the members binds one: no one but the asker can be asked, so a
 * wait is never assigned to an id that may not be a member.
 */
internal object NoOtherMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}

/** No login names a member until the orgs module binds its [MemberLogins]: a wait is asked by member id only. */
internal object NoMemberLogins : MemberLogins {
    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
}

/** No session is known until the sessions module binds its [AgentSessions]: a member asks a wait in none. */
internal object NoAgentSessions : AgentSessions {
    override suspend fun find(orgId: Uuid, sessionId: String): AgentSession? = null
}

/** No agent token is anyone until the launches module binds its [WaitAgentResolver]: the waits MCP refuses it. */
internal object NoWaitAgents : WaitAgentResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? = null
}

/** No one listens to waits until notifications and the waking of sessions bind a [WaitEvents]. */
internal object SilentWaitEvents : WaitEvents {
    override suspend fun created(wait: Wait) = Unit

    override suspend fun resolved(wait: Wait) = Unit

    override suspend fun cancelled(wait: Wait) = Unit
}

/** The launcher until a module serving cloud sessions binds one: every run fails `cloud_sessions_unavailable`. */
internal object UnavailableLauncher : AutomationLauncher {
    override suspend fun launch(request: LaunchRequest): LaunchResult =
        LaunchResult.Refused("cloud_sessions_unavailable")

    override suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean = false

    override suspend fun pause(orgId: Uuid, owner: String, sessionId: String): Boolean = false
}
