package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/** A member's role in an organization. */
enum class OrgRole { OWNER, ADMIN, MEMBER, APPROVER, BILLING }

/**
 * The member an authenticated request acts for, in one organization. Every org-scoped query filters on [orgId].
 *
 * @property orgId The organization.
 * @property memberId The member, stable across the member's tokens and sessions.
 * @property role The member's role in the organization.
 */
data class OrgPrincipal(val orgId: Uuid, val memberId: Uuid, val role: OrgRole)

/**
 * Turns an [AuthenticatedPrincipal] (who the credential belongs to) into the [OrgPrincipal] it acts for in an
 * organization. The identity and orgs modules bind the implementation in Koin; until they do,
 * [serverKoin][dev.ostrom.server.kernel.module.serverKoin] binds [RejectingPrincipalOrgResolver], so no org route
 * is open by mistake.
 */
interface PrincipalOrgResolver {
    /**
     * Resolves the member a principal acts for.
     *
     * @param principal The authenticated principal.
     * @param orgId The organization named by the request, or null when the credential is bound to one organization
     *   (an agent token).
     *
     * @return The member, or null when the principal is not a member of that organization (or of any).
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal?
}

/** Resolves no one: the default until the identity and orgs modules bind theirs. */
class RejectingPrincipalOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? = null
}

/**
 * The member the authenticated caller of an org route acts for.
 *
 * @param resolver The bound [PrincipalOrgResolver].
 * @param orgId The organization named by the request, or null for a credential bound to one organization.
 *
 * @return The member.
 *
 * @throws OstromException A 401 outside [authenticated]; a 404 when the caller is not a member, answered like an
 *   unknown organization so that its existence does not leak.
 */
suspend fun ApplicationCall.requireOrgPrincipal(resolver: PrincipalOrgResolver, orgId: Uuid?): OrgPrincipal =
    resolver.resolve(requirePrincipal(), orgId) ?: throw OstromException(ErrorKind.NOT_FOUND, "Organization not found.")
