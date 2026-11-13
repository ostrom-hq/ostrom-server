// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import run.ostrom.server.events.domain.WebhookActor
import run.ostrom.server.events.domain.WebhooksForbiddenException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.outsideOrganization

/** The roles that manage webhooks: a webhook receives the whole organization's events. */
private val WEBHOOK_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * Whether a credential may read (or, with [write], change) webhooks, before its role is known: a person's browser
 * session or token holding `cloud:read` (`cloud:write` to change), or a service key holding `cloud:admin`. Never an
 * agent's or a device's token, nor a `team:read` key.
 *
 * @param write True for a change.
 *
 * @return True when it may.
 */
internal fun AuthenticatedPrincipal.managesWebhooks(write: Boolean): Boolean = when (kind) {
    PrincipalKind.USER -> allows(CLOUD_WRITE) || (!write && allows(CLOUD_READ))
    PrincipalKind.SERVICE -> ServiceKeyScopes.CLOUD_ADMIN in scopes
    else -> false
}

/**
 * The owner or admin a webhooks call acts for. The scope first: a credential that may not manage webhooks learns
 * nothing about its organization.
 *
 * @param principal The caller.
 * @param write True for a change.
 *
 * @return The actor.
 *
 * @throws WebhooksForbiddenException When the credential or the member's role may not.
 */
internal suspend fun PrincipalOrgResolver.webhookActorOf(
    principal: AuthenticatedPrincipal,
    write: Boolean,
): WebhookActor {
    if (!principal.managesWebhooks(write)) throw WebhooksForbiddenException()
    val member = resolve(principal, null) ?: throw principal.outsideOrganization()
    if (member.role !in WEBHOOK_ROLES) throw WebhooksForbiddenException()
    return WebhookActor(member.orgId, member.memberId, principal)
}

private const val CLOUD_READ = "cloud:read"
private const val CLOUD_WRITE = "cloud:write"
