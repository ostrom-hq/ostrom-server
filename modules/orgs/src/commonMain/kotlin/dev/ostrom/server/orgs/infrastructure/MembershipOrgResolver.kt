// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.orgs.domain.MembersRepository
import kotlin.uuid.Uuid

/**
 * The [PrincipalOrgResolver] of the server: a person's credential acts in an organization they are
 * a member of, with their role there, checked at every request (a removed member loses access at
 * once, whatever token they hold).
 * - Only a person (`USER`) is resolved: an agent, a device or a service is never a member (audit
 *   A2).
 * - A token bound to one organization only acts in that one; asking for another resolves to nobody.
 * - Without an organization named and without a bound one, nobody: there is no implicit current
 *   organization.
 *
 * Deleting an organization removes its members, so a member always belongs to a live organization.
 *
 * @param members The members.
 */
class MembershipOrgResolver(private val members: MembersRepository) : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
        val userId =
            Uuid.parseOrNull(principal.subjectId).takeIf { principal.kind == PrincipalKind.USER }
        val bound = principal.orgId?.let(Uuid::parseOrNull)
        val target = orgId ?: bound
        val allowed = userId != null && target != null && (bound == null || bound == target)
        val member =
            if (allowed) members.findByUser(checkNotNull(target), checkNotNull(userId)) else null
        return member?.let { OrgPrincipal(it.orgId, it.id, it.role) }
    }
}
