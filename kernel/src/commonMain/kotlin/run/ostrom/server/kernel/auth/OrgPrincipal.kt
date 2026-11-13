// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
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
 * [serverKoin][run.ostrom.server.kernel.module.serverKoin] binds [RejectingPrincipalOrgResolver], so no org route
 * is open by mistake.
 */
interface PrincipalOrgResolver {
    /**
     * Resolves the member a principal acts for: in the organization the path names ([orgId]), else the one the
     * [ORG_HEADER] header names ([AuthenticatedPrincipal.namedOrg]), else the one the credential is bound to, else the
     * caller's only organization.
     *
     * @param principal The authenticated principal.
     * @param orgId The organization named by the request's path, or null when the path names none.
     *
     * @return The member, or null when the principal is not a member of that organization (or of any).
     *
     * @throws OrgRequiredException When nothing names the organization and the caller belongs to several.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal?

    /**
     * Reads what a caller is shown of an organization they were resolved in (`GET /api/v1/auth/me`).
     *
     * @param orgId The organization.
     *
     * @return Its short name, name and picture, or null when it is unknown (always, until the orgs module binds its
     *   resolver).
     */
    suspend fun profile(orgId: Uuid): OrgProfile? = null
}

/**
 * An organization as its member is shown it.
 *
 * @property id The id.
 * @property slug The short name.
 * @property name The name.
 * @property avatarUrl Its picture (the linked GitHub organization's), or empty.
 * @property linkedToGitHub Whether it is linked to a GitHub organization.
 */
data class OrgProfile(
    val id: Uuid,
    val slug: String,
    val name: String,
    val avatarUrl: String,
    val linkedToGitHub: Boolean,
)

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
    resolver.resolve(requirePrincipal(), orgId) ?: throw OrganizationNotFoundException()

/**
 * The request names an organization the caller is not a member of (by its path, the [ORG_HEADER] header or a
 * parameter), or the credential acts in none: 404, answered like an unknown organization so that its existence does
 * not leak. Never a 403, which would tell that it exists.
 */
class OrganizationNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/**
 * A credential that is not a person's (an agent token whose launch is over, a device, a service key) acts in no
 * organization here: 403. It never names an organization, so the answer tells nothing about one.
 */
class NoOrganizationCredentialException :
    OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")

/**
 * The refusal of a caller that resolves to no member of the organization its request acts in: a person's credential
 * names an organization they are not in, or none, and gets [OrganizationNotFoundException] (404); any other credential
 * gets [NoOrganizationCredentialException] (403), which says why.
 *
 * @return The exception to throw.
 */
fun AuthenticatedPrincipal.outsideOrganization(): OstromException =
    if (kind == PrincipalKind.USER) OrganizationNotFoundException() else NoOrganizationCredentialException()

/** Nothing names the organization (path, [ORG_HEADER], bound credential) and the caller belongs to several: 409. */
class OrgRequiredException :
    OstromException(ErrorKind.ORG_REQUIRED, "You belong to several organizations: name one in the $ORG_HEADER header.")
