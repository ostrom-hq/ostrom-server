// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import run.ostrom.server.events.domain.WebhookActor
import run.ostrom.server.events.domain.WebhooksForbiddenException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.outsideOrganization

/** The roles that manage webhooks: a webhook receives the whole organization's events. */
private val WEBHOOK_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * Whether a credential may manage webhooks, before its role is known, as TeamCode decides it
 * (`requireCloudAdministrator`): a person's interactive login (a browser session, the unlocked mobile app or the
 * token of `ostrom login`), or a service key holding `cloud:admin` (TeamCode's `admin:cloud`). Never a personal token
 * made in the console whatever its `cloud:*` scopes, an agent's or a device's token, nor a `team:read` key: a webhook
 * receives the whole organization's events.
 *
 * @return True when it may.
 */
internal fun AuthenticatedPrincipal.managesWebhooks(): Boolean = when (kind) {
    PrincipalKind.USER -> mayWrite(WriteAccess.PERSON)
    PrincipalKind.SERVICE -> ServiceKeyScopes.CLOUD_ADMIN in scopes
    else -> false
}

/**
 * The owner or admin a webhooks call acts for. The scope first: a credential that may not manage webhooks learns
 * nothing about its organization.
 *
 * @param principal The caller.
 *
 * @return The actor.
 *
 * @throws WebhooksForbiddenException When the credential or the member's role may not.
 */
internal suspend fun PrincipalOrgResolver.webhookActorOf(principal: AuthenticatedPrincipal): WebhookActor {
    val member = if (principal.managesWebhooks()) {
        resolve(principal, null) ?: throw principal.outsideOrganization()
    } else {
        null
    }
    if (member == null || member.role !in WEBHOOK_ROLES) throw WebhooksForbiddenException()
    return WebhookActor(member.orgId, member.memberId, principal)
}

