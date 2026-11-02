// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRequiredException
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.OrgsRepository
import kotlin.uuid.Uuid

/**
 * The [PrincipalOrgResolver] of the server, the one place that decides which organization a request acts in: a
 * person's credential acts in an organization they are a member of, with their role there, checked at every request
 * (a removed member loses access at once, whatever token they hold).
 * - Only a person (`USER`) is resolved: an agent, a device or a service is never a member (audit A2).
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
        val userId =
            Uuid.parseOrNull(principal.subjectId).takeIf { principal.kind == PrincipalKind.USER }
        val bound = principal.orgId?.let(Uuid::parseOrNull)
        // A header that is not an id names no organization anyone belongs to.
        val named = orgId ?: principal.namedOrg?.let { Uuid.parseOrNull(it) ?: Uuid.NIL }
        val target = named ?: bound
        // A token bound to one organization never acts in another.
        val foreign = bound != null && bound != target
        if (userId == null || foreign) return null
        val member = if (target == null) onlyMembership(userId) else members.findByUser(target, userId)
        return member?.let { OrgPrincipal(it.orgId, it.id, it.role) }
    }

    private suspend fun onlyMembership(userId: Uuid): Member? {
        val memberships = orgs.listOfUser(userId)
        if (memberships.size > 1) throw OrgRequiredException()
        return memberships.singleOrNull()?.member
    }
}
