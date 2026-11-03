// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AgentRunRefusedException
import dev.ostrom.server.automations.domain.AgentSessionRequiredException
import dev.ostrom.server.automations.domain.AutomationCaller
import dev.ostrom.server.automations.domain.AutomationNotFoundException
import dev.ostrom.server.automations.domain.MissingAutomationScopeException
import dev.ostrom.server.automations.domain.MissingWaitScopeException
import dev.ostrom.server.automations.domain.NoOrganizationException
import dev.ostrom.server.automations.domain.WaitAgentResolver
import dev.ostrom.server.automations.domain.WaitCaller
import dev.ostrom.server.automations.domain.WaitNotFoundException
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.outsideOrganization
import dev.ostrom.server.kernel.auth.requireWrite
import kotlin.uuid.Uuid

/** Reading waits. */
const val WAITS_READ = "waits:read"

/** Asking, reading and cancelling waits, never answering: an agent's credential. Includes [WAITS_READ]. */
const val WAITS_ASK = "waits:ask"

/** Everything on waits, answering included. Includes [WAITS_ASK]. */
const val WAITS_WRITE = "waits:write"

private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a credential carries [scope], directly, through a wider waits scope, or as a member's full login. An agent
 * token never reaches the waits API this way, whatever its scopes: it asks only through the waits MCP server, bound to
 * its session ([agentWaitCallerOf]).
 */
fun AuthenticatedPrincipal.allowsWaits(scope: String): Boolean = when {
    kind == PrincipalKind.AGENT -> false
    kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes -> true
    scope in scopes || WAITS_WRITE in scopes -> true
    else -> scope == WAITS_READ && WAITS_ASK in scopes
}

/**
 * The member a request acts for.
 *
 * @throws MissingWaitScopeException When the credential lacks [scope].
 * @throws NoOrganizationException When it acts for no organization.
 */
suspend fun PrincipalOrgResolver.waitCallerOf(principal: AuthenticatedPrincipal, scope: String): WaitCaller {
    if (!principal.allowsWaits(scope)) throw MissingWaitScopeException(scope)
    val member = resolve(principal, null) ?: throw NoOrganizationException()
    return WaitCaller(
        orgId = member.orgId,
        memberId = member.memberId.toString(),
        admin = member.role in ADMIN_ROLES,
        interactive = principal.kind == PrincipalKind.USER,
    )
}

private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/** What an agent token does with waits: ask, read and cancel its session's; never answer (S2-52). */
private val AGENT_WAIT_SCOPES = setOf(WAITS_ASK, WAITS_READ)

/**
 * The member an agent token asks for in the waits MCP server: the person who launched it, bound to one of its launch's
 * sessions (audit A12). It is never an administrator, whatever that person's role (an agent never administers the
 * organization, as in the MCP relay), nor someone who signed in: it reads and cancels only the waits it or its person
 * asked.
 *
 * @param principal The agent token's principal.
 * @param sessionId The session the request names: any of its segments.
 * @param scope What the tool needs.
 *
 * @return The caller, in the segment open now.
 *
 * @throws MissingWaitScopeException When [scope] is more than asking and reading.
 * @throws dev.ostrom.server.kernel.auth.NoOrganizationCredentialException When the token's launch is over or its
 *   person may no longer launch.
 * @throws dev.ostrom.server.kernel.errors.OstromException When the session is not one of its launch's open sessions.
 */
suspend fun WaitAgentResolver.agentWaitCallerOf(
    principal: AuthenticatedPrincipal,
    sessionId: String,
    scope: String,
): WaitCaller {
    if (scope !in AGENT_WAIT_SCOPES) throw MissingWaitScopeException(scope)
    val agent = resolve(principal, sessionId) ?: throw principal.outsideOrganization()
    return WaitCaller(
        orgId = agent.orgId,
        memberId = agent.memberId,
        admin = false,
        interactive = false,
        agent = true,
        session = agent.sessionId,
    )
}

/**
 * The callers of one request to the waits MCP server, by scope, each resolved once per request. A person's
 * credential or a key is the member the orgs module resolves, with its waits scopes ([waitCallerOf]); an agent token
 * is its launch's person ([agentWaitCallerOf]), in the session it names, and never names a run (refused before any
 * message is read). An agent token may name no session yet: Claude Code connects its MCP servers at start-up, maybe
 * before its SessionStart hook told the relay the session, so the handshake and the tools' list are answered and each
 * tool names what is missing.
 *
 * @param principal The caller's principal.
 * @param binding The session and run the request names.
 *
 * @return The caller for a scope; it throws when refused, [AgentSessionRequiredException] for an agent token that
 *   names no session.
 *
 * @throws AgentRunRefusedException When an agent token names a run.
 */
internal fun WaitsRoutesDependencies.mcpCallers(
    principal: AuthenticatedPrincipal,
    binding: WaitsMcpApi,
): suspend (String) -> WaitCaller {
    val resolve: suspend (String) -> WaitCaller = if (principal.kind == PrincipalKind.AGENT) {
        if (binding.run != null) throw AgentRunRefusedException()
        val session = binding.session
        val agentCaller: suspend (String) -> WaitCaller = { scope ->
            agents.agentWaitCallerOf(principal, session ?: throw AgentSessionRequiredException(), scope)
        }
        agentCaller
    } else {
        { scope -> orgResolver.waitCallerOf(principal, scope) }
    }
    // Once per scope and request: a tool may need its caller twice (`cancel_wait` reads, then cancels), and two
    // readings of the token could disagree if its launch ends in between.
    val resolved = mutableMapOf<String, WaitCaller>()
    return { scope -> resolved.getOrPut(scope) { resolve(scope) } }
}

/** A wait id of a path; a malformed one names no wait. */
internal fun waitId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw WaitNotFoundException()

/** Reading automations and their runs. */
const val AUTOMATIONS_READ = "automations:read"

/** Creating, changing, testing and deleting automations. Includes [AUTOMATIONS_READ]. */
const val AUTOMATIONS_WRITE = "automations:write"

/** Who writes automations besides a browser session (S2-52, declared): a service key, made for programs. */
val AUTOMATION_WRITERS: WriteAccess = WriteAccess.tokens(PrincipalKind.SERVICE)

/**
 * Whether a credential carries [scope], directly, through `automations:write`, or as a member's full login. A device
 * or agent token never manages automations, whatever its scopes (S2-03): only a member or a service key does.
 */
fun AuthenticatedPrincipal.allowsAutomations(scope: String): Boolean = when (kind) {
    PrincipalKind.DEVICE, PrincipalKind.AGENT -> false
    PrincipalKind.USER -> ALL_USER_SCOPES in scopes || scope in scopes || AUTOMATIONS_WRITE in scopes
    PrincipalKind.SERVICE -> scope in scopes || AUTOMATIONS_WRITE in scopes
}

/**
 * The member an automations request acts for. Writing ([AUTOMATIONS_WRITE]) takes a browser session or a service key
 * ([AUTOMATION_WRITERS], S2-52): never a personal token, a device or the mobile app.
 *
 * @throws MissingAutomationScopeException When the credential lacks [scope].
 * @throws BrowserSessionRequiredException When it may not write automations.
 * @throws NoOrganizationException When it acts for no organization.
 */
suspend fun PrincipalOrgResolver.automationCallerOf(
    principal: AuthenticatedPrincipal,
    scope: String,
): AutomationCaller {
    if (!principal.allowsAutomations(scope)) throw MissingAutomationScopeException(scope)
    if (scope == AUTOMATIONS_WRITE) principal.requireWrite(AUTOMATION_WRITERS)
    val member = resolve(principal, null) ?: throw NoOrganizationException()
    return AutomationCaller(
        orgId = member.orgId,
        memberId = member.memberId.toString(),
        admin = member.role in ADMIN_ROLES,
        interactive = principal.kind == PrincipalKind.USER,
    )
}

/** An automation id of a path; a malformed one names no automation. */
internal fun automationId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw AutomationNotFoundException()
