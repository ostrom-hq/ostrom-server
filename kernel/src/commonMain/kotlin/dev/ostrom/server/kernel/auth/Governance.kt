// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

/**
 * The governance write scopes (S2-52, decision of 02/11/2026): changing the rules, the harness and the gate policy of
 * an organization. A person's browser session holds them all; the only token that may hold them is the one
 * `ostrom login --admin` gets after an owner or an admin approved them in the browser, bound to that organization and
 * valid 8 hours at most. The identity module withholds them from every other token, whatever its row lists.
 */
object GovernanceScopes {
    /** Decides the rules (accept, reject, edit, bulk) and sets the model policy. */
    const val POLICY_WRITE: String = "policy:write"

    /** Accepts, edits and deletes the workflows (`ostrom harness enforce`). */
    const val HARNESS_WRITE: String = "harness:write"

    /** Sets the gate policy (self-approval). */
    const val GATES_POLICY: String = "gates:policy"

    /** All three, which `ostrom login --admin` asks for together. */
    val ALL: Set<String> = setOf(POLICY_WRITE, HARNESS_WRITE, GATES_POLICY)
}

/**
 * Whether this credential may make the governance write [scope] names: a person's browser session, or a person's API
 * token that holds [scope] (only the token of `ostrom login --admin` can). Never an agent, a device, a service key or
 * the phone app.
 *
 * @param scope One of [GovernanceScopes].
 *
 * @return True when allowed.
 */
fun AuthenticatedPrincipal.governs(scope: String): Boolean = kind == PrincipalKind.USER && when (channel) {
    PrincipalChannel.BROWSER -> ALL_USER_SCOPES in scopes
    PrincipalChannel.API_TOKEN -> scope in scopes
    PrincipalChannel.MOBILE_APP -> false
}

/**
 * Records every governance write a token makes, before the write: the token of `ostrom login --admin` leaves a trace
 * of each use in its organization's audit log, by its person and credential (decision of 02/11/2026). A browser
 * session's writes are not recorded here: each route already keeps them as signed decisions. The identity module binds
 * it; until it does, [configureHttp][dev.ostrom.server.kernel.http.configureHttp] uses [RefusingGovernanceAudit], so
 * no token writes the governance without its trace.
 */
interface GovernanceAudit {
    /**
     * Records one write.
     *
     * @param principal The token's principal.
     * @param scope The governance scope the write uses.
     * @param target The request, as `METHOD /path`.
     */
    suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String)
}

/** The [GovernanceAudit] of a server without the identity module: it refuses every token's governance write. */
class RefusingGovernanceAudit : GovernanceAudit {
    override suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String): Unit =
        throw OstromException(ErrorKind.FORBIDDEN, "This server keeps no audit log for $scope: sign in to the console.")
}

/** The application's [GovernanceAudit], set by [configureHttp][dev.ostrom.server.kernel.http.configureHttp]. */
internal val governanceAuditKey: AttributeKey<GovernanceAudit> = AttributeKey("ostrom.governance.audit")

/**
 * Records a governance write made with a token ([GovernanceAudit]), once the route has checked who may make it and
 * before it changes anything: when the record fails, the write does not happen. A browser session's write records
 * nothing here.
 *
 * @param principal Who writes.
 * @param scope The governance scope the write uses ([GovernanceScopes]).
 */
suspend fun ApplicationCall.auditGovernance(principal: AuthenticatedPrincipal, scope: String) {
    if (principal.channel != PrincipalChannel.API_TOKEN) return
    application.attributes[governanceAuditKey].record(principal, scope, "${request.httpMethod.value} ${request.path()}")
}
