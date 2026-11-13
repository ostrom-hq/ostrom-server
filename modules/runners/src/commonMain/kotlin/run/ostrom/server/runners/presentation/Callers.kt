// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.MissingScopeException
import run.ostrom.server.runners.domain.NotAMemberException

/** The scope that reads cloud sessions. */
const val CLOUD_READ = "cloud:read"

/** The scope that creates, pauses, resumes, deletes and attaches to cloud sessions. */
const val CLOUD_WRITE = "cloud:write"

/** A user's browser session, which may do everything its user may (the identity module's `ALL_USER_SCOPES`). */
private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a principal's scopes allow a cloud action: the scope itself, [CLOUD_WRITE] for [CLOUD_READ],
 * [ServiceKeyScopes.CLOUD_ADMIN] for both, or a user's browser session.
 *
 * @param scope The scope the action needs.
 *
 * @return True when allowed.
 */
fun AuthenticatedPrincipal.allowsCloud(scope: String): Boolean =
    scope in scopes ||
        (scope == CLOUD_READ && CLOUD_WRITE in scopes) ||
        (kind == PrincipalKind.SERVICE && ServiceKeyScopes.CLOUD_ADMIN in scopes) ||
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
    // A service key is a plain member, and an administrator over the cloud sessions only through `cloud:admin` (the
    // resolver has checked that its creator still is one).
    val administers = if (principal.kind == PrincipalKind.SERVICE) {
        ServiceKeyScopes.CLOUD_ADMIN in principal.scopes
    } else {
        member.role in ADMIN_ROLES
    }
    return Caller(member.orgId, member.memberId.toString(), administers)
}

/** The roles that administer the organization's runners. */
private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)
