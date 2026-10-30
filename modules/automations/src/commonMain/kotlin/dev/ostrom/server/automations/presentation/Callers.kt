// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AutomationCaller
import dev.ostrom.server.automations.domain.AutomationNotFoundException
import dev.ostrom.server.automations.domain.MissingAutomationScopeException
import dev.ostrom.server.automations.domain.MissingWaitScopeException
import dev.ostrom.server.automations.domain.NoOrganizationException
import dev.ostrom.server.automations.domain.WaitCaller
import dev.ostrom.server.automations.domain.WaitNotFoundException
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.uuid.Uuid

/** Reading waits. */
const val WAITS_READ = "waits:read"

/** Asking, reading and cancelling waits, never answering: an agent's credential. Includes [WAITS_READ]. */
const val WAITS_ASK = "waits:ask"

/** Everything on waits, answering included. Includes [WAITS_ASK]. */
const val WAITS_WRITE = "waits:write"

private const val ALL_USER_SCOPES = "user:*"

/** Whether a credential carries [scope], directly, through a wider waits scope, or as a member's full login. */
fun AuthenticatedPrincipal.allowsWaits(scope: String): Boolean = when {
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

/** A wait id of a path; a malformed one names no wait. */
internal fun waitId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw WaitNotFoundException()

/** Reading automations and their runs. */
const val AUTOMATIONS_READ = "automations:read"

/** Creating, changing, testing and deleting automations. Includes [AUTOMATIONS_READ]. */
const val AUTOMATIONS_WRITE = "automations:write"

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
 * The member an automations request acts for.
 *
 * @throws MissingAutomationScopeException When the credential lacks [scope].
 * @throws NoOrganizationException When it acts for no organization.
 */
suspend fun PrincipalOrgResolver.automationCallerOf(
    principal: AuthenticatedPrincipal,
    scope: String,
): AutomationCaller {
    if (!principal.allowsAutomations(scope)) throw MissingAutomationScopeException(scope)
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
