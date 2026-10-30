// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgNotFoundException
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.ScopeRequiredException
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/**
 * The member an authenticated call acts for, in the org of its credential, after checking that the credential may
 * call the route (SECURITY-AUDIT S2-03): a device credential never may; a user's browser session always may; any other
 * token must list one of [scopes].
 *
 * @param resolver Resolves principals.
 * @param scopes The scopes that open the route; one is enough.
 *
 * @return The caller.
 *
 * @throws ScopeRequiredException When the credential may not call the route.
 * @throws OrgNotFoundException When the principal is not a member of an org.
 */
suspend fun ApplicationCall.requireCaller(resolver: CallerResolver, vararg scopes: String): Caller {
    val principal = requirePrincipal()
    if (!principal.mayUse(scopes)) throw ScopeRequiredException(scopes.first())
    return resolver.resolve(principal, null) ?: throw OrgNotFoundException()
}

/**
 * Whether a principal may call a route opened by [scopes] (see [requireCaller]).
 *
 * @param scopes The route's scopes.
 *
 * @return True when allowed.
 */
fun AuthenticatedPrincipal.mayUse(scopes: Array<out String>): Boolean = when {
    kind == PrincipalKind.DEVICE -> false
    kind == PrincipalKind.USER && RelayScopes.ALL_USER in this.scopes -> true
    else -> scopes.any { it in this.scopes }
}

/**
 * Parses an id of the URL.
 *
 * @param notFound The exception for a malformed id, the same as for an unknown one.
 *
 * @return The id.
 */
fun String.toIdOr(notFound: () -> Exception): Uuid = Uuid.parseOrNull(this) ?: throw notFound()
