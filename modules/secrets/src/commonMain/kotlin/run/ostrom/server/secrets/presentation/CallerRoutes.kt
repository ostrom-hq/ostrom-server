// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.BrowserSessionRequiredException
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireWrite
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.OrgNotFoundException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.ScopeRequiredException
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
 * @param scopes The scopes that open the route; one is enough. A route that lists [ServiceKeyScopes.TEAM_READ] is a
 *   read a `team:read` key makes as an administrator would; any other route sees a service key as a plain member.
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
    val caller = resolver.resolve(principal, null) ?: throw OrgNotFoundException()
    return caller.forKey(principal, ServiceKeyScopes.TEAM_READ in scopes)
}

/**
 * What a service key may do as its creator: a plain member, as the resolver gives it, unless the route is one of the
 * reads `team:read` opens ([teamRead]) and the key holds it: those see the organization as an administrator does (the
 * resolver has checked that its creator still is one). A person's credential is unchanged.
 */
fun Caller.forKey(principal: AuthenticatedPrincipal, teamRead: Boolean = false): Caller =
    if (principal.kind == PrincipalKind.SERVICE && teamRead && ServiceKeyScopes.TEAM_READ in principal.scopes) {
        copy(role = MemberRole.ADMIN)
    } else {
        this
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
