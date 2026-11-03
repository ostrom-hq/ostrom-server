// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

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
 * the phone app. A token's write is not applied: it waits for a confirmation ([confirmsByConsole]).
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
 * Whether a governance write of this credential waits for an owner or an admin to confirm it on the console (audit CLI
 * H1, decision of 02/11/2026): a person's API token, the CLI's (elevated by `ostrom login --admin` or not) or a
 * personal one. An agent runs on the developer's machine and can present any of them, so none writes the governance
 * itself; it opens a [GovernanceConfirmations] request. A browser session writes at once.
 */
val AuthenticatedPrincipal.confirmsByConsole: Boolean
    get() = kind == PrincipalKind.USER && channel == PrincipalChannel.API_TOKEN

/**
 * A governance write held for confirmation, as the module that owns it describes it: [kind] says which, [payload]
 * holds what the module needs to make it later (the path's ids and the body), exactly as it was asked.
 *
 * @property kind The contract's `GovernanceChangeKind`, such as `gates.policy`.
 * @property payload What the owning module reads back to preview and apply it.
 */
data class GovernanceWrite(val kind: String, val payload: JsonObject)

/**
 * What a governance write changes, shown before anyone confirms it.
 *
 * @property summary One line saying what it does.
 * @property before What it replaces, as the read routes show it; null for nothing (a creation).
 * @property after What it writes, as the read routes show it; null for nothing (a deletion).
 */
data class GovernancePreview(val summary: String, val before: JsonElement?, val after: JsonElement?)

/**
 * The person who confirms a governance write, signed in to the console: the module applies the write as their signed
 * decision.
 *
 * @property principal Their browser session, naming the organization of the confirmation.
 * @property member Their membership there, an owner or an admin.
 */
data class GovernanceConfirmer(val principal: AuthenticatedPrincipal, val member: OrgPrincipal)

/**
 * The governance writes one module owns (rules, workflows, model policy): it previews them when a token asks, and makes
 * them once an owner or an admin confirmed. Each module binds one, named after itself.
 */
interface GovernanceChange {
    /** The [GovernanceWrite.kind]s it owns. */
    val kinds: Set<String>

    /**
     * Reads what [write] would change now, without changing anything.
     *
     * @param orgId The organization.
     * @param write The write.
     *
     * @return What it replaces and what it writes.
     *
     * @throws OstromException When it cannot be made (an unknown rule, an invalid body): no confirmation is opened.
     */
    suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview

    /**
     * Makes [write], as [confirmer]'s decision.
     *
     * @param orgId The organization.
     * @param write The write, as it was asked.
     * @param confirmer Who confirmed it.
     *
     * @return The body the route would have answered, or null when it answers none (a deletion).
     *
     * @throws OstromException When the write is refused now (a version conflict, a rule deleted meanwhile).
     */
    suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement?
}

/**
 * A governance write a token asked for, with who asked and where.
 *
 * @property principal The token's principal.
 * @property orgId The organization the route resolved.
 * @property scope The governance scope the write uses ([GovernanceScopes]).
 * @property method The request's method.
 * @property path The request's path.
 * @property write The write itself.
 */
data class GovernanceRequest(
    val principal: AuthenticatedPrincipal,
    val orgId: Uuid,
    val scope: String,
    val method: String,
    val path: String,
    val write: GovernanceWrite,
)

/**
 * The 202 of a governance write made with a token (contract `GovernanceConfirmationPending`): nothing was written.
 *
 * @property confirmationId The confirmation.
 * @property confirmUrl The console page where an owner or an admin confirms it.
 * @property expiresAt When it expires (ISO 8601), 15 minutes after it was opened.
 */
@Serializable
data class GovernanceConfirmationPending(val confirmationId: String, val confirmUrl: String, val expiresAt: String)

/**
 * Holds the governance writes of tokens until an owner or an admin confirms them on the console. The governance module
 * binds it; until it does, [configureHttp][dev.ostrom.server.kernel.http.configureHttp] uses
 * [RefusingGovernanceConfirmations], so no token ever writes the governance.
 */
interface GovernanceConfirmations {
    /**
     * Opens a confirmation for [request], or answers the one its person already opened for the same change.
     *
     * @param request The write.
     *
     * @return What the token is answered.
     *
     * @throws OstromException When it cannot be opened: the person is not an owner or an admin, the change cannot be
     *   made, too many wait already, or the server keeps no audit log.
     */
    suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending
}

/** The [GovernanceConfirmations] of a server without the governance module: a token's governance write is refused. */
class RefusingGovernanceConfirmations : GovernanceConfirmations {
    override suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending =
        throw OstromException(ErrorKind.FORBIDDEN, "This server confirms no governance change: sign in to the console.")
}

/** The application's [GovernanceConfirmations], set by [configureHttp][dev.ostrom.server.kernel.http.configureHttp]. */
internal val governanceConfirmationsKey: AttributeKey<GovernanceConfirmations> =
    AttributeKey("ostrom.governance.confirmations")

/**
 * Holds a governance write made with a token ([confirmsByConsole]) for confirmation, once the route has checked who
 * may make it and before it changes anything, and answers 202 with the confirmation. Nothing is written.
 *
 * @param orgId The organization the route resolved.
 * @param scope The governance scope the write uses.
 * @param write The write, as the owning module will make it.
 */
suspend fun ApplicationCall.holdForConfirmation(orgId: Uuid, scope: String, write: GovernanceWrite) {
    val request = GovernanceRequest(requirePrincipal(), orgId, scope, request.httpMethod.value, request.path(), write)
    respond(HttpStatusCode.Accepted, application.attributes[governanceConfirmationsKey].open(request))
}

/**
 * Records each use of the token of `ostrom login --admin` that is not a held governance write (starting an analysis or
 * a replay of the rules), before it happens, in its organization's audit log, by its person and credential (decision of
 * 02/11/2026). A governance write made with a token is never recorded here: it is held ([holdForConfirmation]) and the
 * governance module records each step of its confirmation. The identity module binds it; until it does,
 * [configureHttp][dev.ostrom.server.kernel.http.configureHttp] uses [RefusingGovernanceAudit], so no token acts
 * without its trace.
 */
interface GovernanceAudit {
    /**
     * Records one use.
     *
     * @param principal The token's principal.
     * @param scope The governance scope the action uses.
     * @param target The request, as `METHOD /path`.
     */
    suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String)
}

/** The [GovernanceAudit] of a server without the identity module: it refuses every token's governed action. */
class RefusingGovernanceAudit : GovernanceAudit {
    override suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String): Unit =
        throw OstromException(ErrorKind.FORBIDDEN, "This server keeps no audit log for $scope: sign in to the console.")
}

/** The application's [GovernanceAudit], set by [configureHttp][dev.ostrom.server.kernel.http.configureHttp]. */
internal val governanceAuditKey: AttributeKey<GovernanceAudit> = AttributeKey("ostrom.governance.audit")

/**
 * Records a governed action made with a token ([GovernanceAudit]), once the route has checked who may make it and
 * before it changes anything: when the record fails, the action does not happen. A browser session records nothing
 * here.
 *
 * @param principal Who acts.
 * @param scope The governance scope the action uses ([GovernanceScopes]).
 */
suspend fun ApplicationCall.auditGovernance(principal: AuthenticatedPrincipal, scope: String) {
    if (principal.channel != PrincipalChannel.API_TOKEN) return
    application.attributes[governanceAuditKey].record(principal, scope, "${request.httpMethod.value} ${request.path()}")
}
