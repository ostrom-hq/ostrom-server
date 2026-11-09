// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.orgs.domain.MembersRepository
import kotlin.uuid.Uuid

/**
 * [LauncherResolver] on the orgs module's membership check and the identity module's people: a person's credential
 * (CLI, personal token or browser session) acts in the organization the orgs module resolves for it (the one it is
 * bound to, the request's `Ostrom-Org`, or the person's only one), with their role checked at each request.
 *
 * @param orgs Resolves the organization of a principal.
 * @param users The people.
 * @param members The members, for the owner of a cloud session.
 */
class IdentityLauncherResolver(
    private val orgs: PrincipalOrgResolver,
    private val users: UsersRepository,
    private val members: MembersRepository,
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
    override suspend fun member(orgId: Uuid, userId: Uuid): OrgPrincipal? {
        val person = AuthenticatedPrincipal(userId.toString(), PrincipalKind.USER, emptySet(), orgId = orgId.toString())
        return orgs.resolve(person, orgId)
    }

    override suspend fun owner(orgId: Uuid, memberId: Uuid): Launcher? {
        val member = members.findById(orgId, memberId) ?: return null
        return users.get(member.userId)?.let { Launcher(orgId, it.id, it.login, member.role) }
    }

    override suspend fun colleague(orgId: Uuid, login: String): Launcher? {
        val user = users.findByLogin(login) ?: return null
        return members.findByUser(orgId, user.id)?.let { Launcher(orgId, user.id, user.login, it.role) }
    }
}
