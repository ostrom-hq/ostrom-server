// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/** A member's role in an org: only owners and admins change the org's secrets, servers and accounts. */
enum class MemberRole {
    OWNER,
    ADMIN,
    MEMBER,
    ;

    /** Whether the role administers the org. */
    val isAdmin: Boolean get() = this != MEMBER
}

/**
 * The member an authenticated request acts for, in one org. Every query of the RELAY modules is scoped by [orgId].
 *
 * @property orgId The org.
 * @property memberId The member, stable across tokens.
 * @property role The member's role in the org.
 * @property launchId The launch whose agent token made the request (`POST /launches`), or null for the member's own
 *   credential: the journal and the usage events keep it, so an agent's call is told from the person's.
 * @property workflowId The workflow that launch plays (`createLaunch.workflowId`), or null: the model calls of an agent
 *   whose launch names a workflow count under it, for its budgets and its spending.
 * @property caps The limits an administrator set on that launch (`PUT /launches/{launchId}/limits`), or null for a
 *   launch without any: the gateway applies them to the launch's model calls, besides the member's own limits.
 */
data class Caller(
    val orgId: Uuid,
    val memberId: Uuid,
    val role: MemberRole,
    val launchId: String? = null,
    val workflowId: String? = null,
    val caps: LaunchCaps? = null,
) {
    /**
     * Refuses a member who does not administer the org.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     */
    fun requireAdmin() {
        if (!role.isAdmin) throw AdminRequiredException()
    }
}

/**
 * The limits of one launch (`launches.limits.v1`, TeamCode's run policy): additional caps on the model calls of its
 * agent token, never a substitute for the member's own limits. Zero adds no cap.
 *
 * @property budgetMicros What the launch may spend over its life, USD millionths; 0 for no cap.
 * @property maxConcurrent The most calls the launch runs at once; 0 for no cap.
 * @property paused Whether the launch's next calls are refused.
 */
data class LaunchCaps(val budgetMicros: Long = 0, val maxConcurrent: Int = 0, val paused: Boolean = false) {
    /** Whether any limit applies. */
    val any: Boolean get() = budgetMicros > 0 || maxConcurrent > 0 || paused
}

/**
 * Turns a principal into the member it acts for. The modules use [OrgPrincipalCallerResolver], on the kernel's
 * [PrincipalOrgResolver]: until identity binds it, the kernel's default refuses everyone, so no route is open by
 * mistake.
 */
interface CallerResolver {
    /**
     * Resolves the caller.
     *
     * @param principal The authenticated principal.
     * @param orgId The org named by the URL, or null for agent routes whose token is bound to one org.
     *
     * @return The caller, or null when the principal is not a member of that org (or of any org).
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller?
}

/** Refuses every principal (tests and modules loaded without the kernel's resolver). */
class UnresolvedCallerResolver : CallerResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? = null
}

/**
 * [CallerResolver] on the kernel's [PrincipalOrgResolver], which the identity and orgs modules bind: owners and admins
 * administer, every other role (member, approver, billing) is a member here.
 *
 * @param orgs The kernel's resolver.
 */
class OrgPrincipalCallerResolver(private val orgs: PrincipalOrgResolver) : CallerResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
        orgs.resolve(principal, orgId)?.let {
            Caller(
                orgId = it.orgId,
                memberId = it.memberId,
                role = when (it.role) {
                    OrgRole.OWNER -> MemberRole.OWNER
                    OrgRole.ADMIN -> MemberRole.ADMIN
                    OrgRole.MEMBER, OrgRole.APPROVER, OrgRole.BILLING -> MemberRole.MEMBER
                },
            )
        }
}

/** The caller is not a member of the org: answered like an unknown org, so existence does not leak. */
class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** The action needs an org owner or admin. */
class AdminRequiredException : OstromException(ErrorKind.FORBIDDEN, "Only an organization admin can do this.")
