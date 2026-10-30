package dev.ostrom.server.secrets.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
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
 */
data class Caller(val orgId: Uuid, val memberId: Uuid, val role: MemberRole) {
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
