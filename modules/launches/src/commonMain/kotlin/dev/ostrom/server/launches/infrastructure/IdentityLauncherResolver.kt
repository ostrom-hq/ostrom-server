// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.services.LauncherResolver
import kotlin.uuid.Uuid

/**
 * [LauncherResolver] on the orgs module's membership check and the identity module's people: a person's credential
 * (CLI, personal token or browser session) acts in the organization the orgs module resolves for it (the one it is
 * bound to, the request's `Ostrom-Org`, or the person's only one), with their role checked at each request.
 *
 * @param orgs Resolves the organization of a principal.
 * @param users The people.
 */
class IdentityLauncherResolver(
    private val orgs: PrincipalOrgResolver,
    private val users: UsersRepository,
) : LauncherResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal): Launcher? {
        // Only a person launches: an agent token, a device or a service never acts as a member (audit A2).
        val userId = Uuid.parseOrNull(principal.subjectId)?.takeIf { principal.kind == PrincipalKind.USER }
            ?: return null
        return orgs.resolve(principal, null)?.let { member ->
            users.get(userId)?.let { Launcher(member.orgId, it.id, it.login, member.role) }
        }
    }

    // The person as a credential bound to the organization: the orgs module reads their membership as it is now.
    override suspend fun role(orgId: Uuid, userId: Uuid): OrgRole? {
        val person = AuthenticatedPrincipal(userId.toString(), PrincipalKind.USER, emptySet(), orgId = orgId.toString())
        return orgs.resolve(person, orgId)?.role
    }
}
