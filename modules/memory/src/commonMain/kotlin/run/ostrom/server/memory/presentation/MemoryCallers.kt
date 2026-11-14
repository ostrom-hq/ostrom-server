// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.MemoryApi
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryScopeException
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * Who a memory request acts for (TeamCode's `requireMemory`): a person (a browser session, the unlocked app, the CLI's
 * sign-in: TeamCode's interactive login), or a credential holding `memory:use` (an agent token issued with it, a
 * personal token, a service key acting as its creator). An agent token is the member who launched it, as a plain
 * member; a person administers when their role says so.
 *
 * @param callers The server's caller resolution (people and agent tokens).
 */
class MemoryCallers(private val callers: CallerResolver) {
    /**
     * @param principal The credential.
     * @param origin The `x-ostrom-origin` header: `agent` when the CLI's relay forwards the call for an agent.
     * @return The caller.
     * @throws MemoryScopeException For a credential that is no person's and lacks `memory:use`.
     */
    suspend fun of(principal: AuthenticatedPrincipal, origin: String?): MemoryCaller {
        val person = principal.mayWrite(WriteAccess.PERSON)
        if (!person && MemoryApi.SCOPE !in principal.scopes) throw MemoryScopeException()
        val caller = callers.resolve(principal, null) ?: throw principal.outsideOrganization()
        val agent = principal.kind == PrincipalKind.AGENT || origin == MemoryApi.AGENT_ORIGIN
        return MemoryCaller(
            orgId = caller.orgId,
            memberId = caller.memberId.toString(),
            admin = person && caller.role.isAdmin,
            person = person,
            agent = agent,
            principal = principal,
        )
    }
}
