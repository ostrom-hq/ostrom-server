// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgProfile
import run.ostrom.server.kernel.auth.OrgRequiredException
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.OrgsRepository
import kotlin.uuid.Uuid

/**
 * The [PrincipalOrgResolver] of the server, the one place that decides which organization a request acts in: a
 * person's credential acts in an organization they are a member of, with their role there, checked at every request
 * (a removed member loses access at once, whatever token they hold).
 * - Only a person (`USER`) is resolved: an agent, a device or a service is never a member (audit A2). One exception: a
 *   service key that holds member scopes ([AuthenticatedPrincipal.onBehalfOf]) acts as its creator, in the one
 *   organization it is bound to, always as a plain member: the routes a reserved scope opens
 *   ([ServiceKeyScopes.RESERVED]) raise it themselves, and the key holding one is resolved only while its creator is an
 *   owner or an admin. A creator who left the organization leaves the key with none, at the next request.
 * - The organization is the one the path names, else the one the `Ostrom-Org` header names, else the one the token
 *   is bound to. A token bound to one organization only acts in that one; naming another resolves to nobody.
 * - When nothing names it, the caller's only organization is taken (no ambiguity is possible); with several, the
 *   request must name one ([OrgRequiredException], 409); with none, nobody.
 * - A header that is not an organization id, or names one the caller is not a member of, resolves to nobody (a 404
 *   that does not tell whether the organization exists).
 *
 * Deleting an organization removes its members, so a member always belongs to a live organization.
 *
 * @param orgs The organizations, for the caller's only one.
 * @param members The members.
 */
class MembershipOrgResolver(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
) : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
        val service = principal.kind == PrincipalKind.SERVICE
        val userId = when {
            service -> principal.onBehalfOf?.let(Uuid::parseOrNull)
            else -> Uuid.parseOrNull(principal.subjectId).takeIf { principal.kind == PrincipalKind.USER }
        }
        val bound = principal.orgId?.let(Uuid::parseOrNull)
        // A header that is not an id names no organization anyone belongs to.
        val named = orgId ?: principal.namedOrg?.let { Uuid.parseOrNull(it) ?: Uuid.NIL }
        val target = named ?: bound
        // A token bound to one organization never acts in another.
        val foreign = bound != null && bound != target
        // A key is bound to its organization by its row: without one, it acts in none.
        val unbound = service && bound == null
        if (userId == null || foreign || unbound) return null
        val member = if (target == null) onlyMembership(userId) else members.findByUser(target, userId)
        return member?.let { actingAs(principal, OrgPrincipal(it.orgId, it.id, it.role)) }
    }

    /**
     * A person acts with their role; a key always as a plain member, and not at all with a reserved scope when its
     * creator is no longer an owner or an admin.
     */
    private fun actingAs(principal: AuthenticatedPrincipal, member: OrgPrincipal): OrgPrincipal? {
        if (principal.kind != PrincipalKind.SERVICE) return member
        val reserved = principal.scopes.any { it in ServiceKeyScopes.RESERVED }
        val administers = member.role == OrgRole.OWNER || member.role == OrgRole.ADMIN
        return if (reserved && !administers) null else member.copy(role = OrgRole.MEMBER)
    }

    override suspend fun profile(orgId: Uuid): OrgProfile? =
        orgs.get(orgId)?.let { OrgProfile(it.id, it.slug, it.name, it.avatarUrl, it.githubOrgId != null) }

    private suspend fun onlyMembership(userId: Uuid): Member? {
        val memberships = orgs.listOfUser(userId)
        if (memberships.size > 1) throw OrgRequiredException()
        return memberships.singleOrNull()?.member
    }
}
