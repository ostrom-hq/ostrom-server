// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * Resolves `Ostrom-On-Behalf-Of` for a cloud session write (`runners.cloud.onbehalf.v1`, TeamCode's
 * `CloudSessionService.actor`). The header never grants a right: the call is authorized with the caller's own rights.
 * A creation is the effective member's; any other write stays on the caller's own sessions and only journals the
 * effective member ([Caller.ownSessionsOnly]).
 */
interface ResolveCloudActorUseCase {
    /**
     * The member a write acts for. Absent, or naming the caller (by id or login), it is the caller. Only an
     * administrator signed in or with a personal token, or an administrator's `cloud:admin` service key, names
     * another member, who must be an active member of the organization.
     *
     * @param caller The caller, as the credential resolves.
     * @param principal The credential, kept for the audit log.
     * @param onBehalfOf The member the header names, by id or login (an id wins), or null.
     *
     * @return The caller, acting for the effective member.
     *
     * @throws OnBehalfForbiddenException When a caller who may not act for someone else names another member.
     * @throws InvalidEffectiveMemberException When the member named is not an active member of the organization.
     */
    suspend operator fun invoke(caller: Caller, principal: AuthenticatedPrincipal, onBehalfOf: String?): Caller

    /**
     * The caller of a write that is the owner's alone (GitHub access, the permission bypass): the header may only
     * name the caller themselves (TeamCode's `setGitHubAccess`, `setPermissionBypass`).
     *
     * @param caller The caller, as the credential resolves.
     * @param principal The credential.
     * @param onBehalfOf The member the header names, or null.
     * @param refusal The refusal when it names another member.
     *
     * @return The caller, acting for themselves.
     */
    suspend fun owner(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        onBehalfOf: String?,
        refusal: () -> OstromException,
    ): Caller
}

/**
 * [ResolveCloudActorUseCase] on the organization's members.
 *
 * @param members The members, by id or login; null on a server without the orgs module, which does not announce the
 *   capability and so ignores the header.
 */
class ResolveCloudActorUseCaseImpl(private val members: TransferRecipients?) : ResolveCloudActorUseCase {
    @Suppress("ReturnCount") // The caller themselves, by id then by login, then the member named.
    override suspend fun invoke(caller: Caller, principal: AuthenticatedPrincipal, onBehalfOf: String?): Caller {
        val self = caller.copy(credentialId = principal.credentialId, principal = principal)
        if (onBehalfOf == null || onBehalfOf == caller.memberId || members == null) return self
        val target = members.find(caller.orgId, onBehalfOf)
        // The caller's own login names the caller, whoever they are.
        if (target == caller.memberId) return self
        if (!administers(caller, principal)) throw OnBehalfForbiddenException()
        if (target == null || !members.eligible(caller.orgId, target)) throw InvalidEffectiveMemberException()
        return self.copy(
            memberId = target,
            memberAdmin = members.administers(caller.orgId, target),
            effectiveMemberId = target,
        )
    }

    override suspend fun owner(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        onBehalfOf: String?,
        refusal: () -> OstromException,
    ): Caller {
        val self = caller.copy(credentialId = principal.credentialId, principal = principal)
        if (onBehalfOf == null || onBehalfOf == caller.memberId || members == null) return self
        if (members.find(caller.orgId, onBehalfOf) != caller.memberId) throw refusal()
        return self
    }

    // A person signed in (or their personal token) or a service key, administering: `cloud:admin` for a key, which
    // callerOf already turned into `admin`. An agent's or a device's token never acts for someone else.
    private fun administers(caller: Caller, principal: AuthenticatedPrincipal): Boolean =
        caller.admin && (principal.kind == PrincipalKind.USER || principal.kind == PrincipalKind.SERVICE)
}
