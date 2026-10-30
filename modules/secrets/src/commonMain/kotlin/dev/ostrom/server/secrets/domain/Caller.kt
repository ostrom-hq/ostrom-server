package dev.ostrom.server.secrets.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
 * Turns a principal into the member it acts for. Bound by the identity/orgs modules; until they bind theirs,
 * [UnresolvedCallerResolver] refuses everyone, so no route of these modules is open by mistake.
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

/** Refuses every principal: the default until identity binds its own resolver. */
class UnresolvedCallerResolver : CallerResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? = null
}

/** The caller is not a member of the org: answered like an unknown org, so existence does not leak. */
class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** The action needs an org owner or admin. */
class AdminRequiredException : OstromException(ErrorKind.FORBIDDEN, "Only an organization admin can do this.")
