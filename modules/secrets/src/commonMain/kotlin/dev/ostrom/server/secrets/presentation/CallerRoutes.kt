// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireWrite
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgNotFoundException
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.ScopeRequiredException
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import kotlin.uuid.Uuid

/**
 * The member an authenticated call acts for, in the org of its credential, after checking that the credential may
 * call the route (SECURITY-AUDIT S2-03): a device credential never may; a user's browser session always may; any other
 * token must list one of [scopes]. A write (any method but GET, HEAD and OPTIONS) is a console write: it also needs
 * a browser session, unless the route admits more in [writers] (S2-52), so a personal or agent token holding a
 * write scope still cannot make a console change by construction.
 *
 * @param resolver Resolves principals.
 * @param scopes The scopes that open the route; one is enough.
 * @param writers Who may call the route when it writes, besides a browser session; nobody else by default.
 *
 * @return The caller.
 *
 * @throws ScopeRequiredException When the credential may not call the route.
 * @throws BrowserSessionRequiredException When the route writes and does not admit the credential.
 * @throws OrgNotFoundException When the principal is not a member of an org.
 */
suspend fun ApplicationCall.requireCaller(
    resolver: CallerResolver,
    vararg scopes: String,
    writers: WriteAccess = WriteAccess.BROWSER,
): Caller {
    val principal = requirePrincipal()
    if (!principal.mayUse(scopes)) throw ScopeRequiredException(scopes.first())
    if (request.httpMethod !in READ_METHODS) principal.requireWrite(writers)
    return resolver.resolve(principal, null) ?: throw OrgNotFoundException()
}

/** The methods that change nothing: every other one is a write. */
private val READ_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

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
