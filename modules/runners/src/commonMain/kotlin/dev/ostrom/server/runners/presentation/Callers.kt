// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.MissingScopeException
import dev.ostrom.server.runners.domain.NotAMemberException

/** The scope that reads cloud sessions. */
const val CLOUD_READ = "cloud:read"

/** The scope that creates, pauses, resumes, deletes and attaches to cloud sessions. */
const val CLOUD_WRITE = "cloud:write"

/** A user's browser session, which may do everything its user may (the identity module's `ALL_USER_SCOPES`). */
private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a principal's scopes allow a cloud action: the scope itself, [CLOUD_WRITE] for [CLOUD_READ], or a user's
 * browser session.
 *
 * @param scope The scope the action needs.
 *
 * @return True when allowed.
 */
fun AuthenticatedPrincipal.allowsCloud(scope: String): Boolean =
    scope in scopes ||
        (scope == CLOUD_READ && CLOUD_WRITE in scopes) ||
        (kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes)

/**
 * The member a cloud request acts for. The organization is the one the credential is bound to (the contract's
 * routes name none), resolved by the kernel's [PrincipalOrgResolver].
 *
 * @param principal The authenticated principal.
 * @param scope The scope the action needs.
 *
 * @return The caller.
 *
 * @throws MissingScopeException When the token's scopes do not allow the action.
 * @throws NotAMemberException When the principal acts for no organization.
 */
suspend fun PrincipalOrgResolver.callerOf(principal: AuthenticatedPrincipal, scope: String): Caller {
    if (!principal.allowsCloud(scope)) throw MissingScopeException(scope)
    val member = resolve(principal, null) ?: throw NotAMemberException()
    return Caller(member.orgId, member.memberId.toString(), member.role in ADMIN_ROLES)
}

/** The roles that administer the organization's runners. */
private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)
