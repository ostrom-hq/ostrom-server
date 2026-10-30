// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlin.uuid.Uuid

/** The roles that may buy, change or cancel the subscription and read invoices. */
val BILLING_ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING)

/**
 * Checks that the caller acts for [orgId].
 *
 * @throws BillingAccessDeniedException When the caller belongs to another organization (answered 404, no leak).
 */
fun OrgPrincipal.requireMemberOf(orgId: Uuid) {
    if (this.orgId != orgId) throw BillingAccessDeniedException()
}

/**
 * Checks that the caller is an owner, an admin or a billing admin of [orgId].
 *
 * @throws BillingAccessDeniedException When the caller belongs to another organization.
 * @throws BillingAdminRequiredException When the caller is a member without one of [BILLING_ADMIN_ROLES].
 */
fun OrgPrincipal.requireBillingAdminOf(orgId: Uuid) {
    requireMemberOf(orgId)
    if (role !in BILLING_ADMIN_ROLES) throw BillingAdminRequiredException()
}

/** Reading an organization's billing (overview, invoices). */
const val BILLING_READ = "billing:read"

/**
 * The scope a token was once issued to change billing with. It no longer allows any write (S2-52: a billing change is
 * made in the console only); a token that still carries it can read, as with [BILLING_READ].
 */
const val BILLING_MANAGE = "billing:manage"

/**
 * The full scope of a member's interactive console session. The identity module never puts it on a token (its
 * `NEVER_ON_A_TOKEN`), so it marks a browser session until the kernel's `WriteAccess` (S2-52) replaces this check.
 */
private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a credential may read billing: only a person (never a device, an agent session or a service), signed in
 * to the console or with a token carrying [BILLING_READ] (or [BILLING_MANAGE]).
 */
fun AuthenticatedPrincipal.allowsBillingRead(): Boolean = kind == PrincipalKind.USER &&
    (BILLING_READ in scopes || BILLING_MANAGE in scopes || ALL_USER_SCOPES in scopes)

/**
 * Whether a credential may change billing: a person signed in to the console only, never a token (S2-52).
 */
fun AuthenticatedPrincipal.allowsBillingWrite(): Boolean = kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes

/**
 * Refuses a billing change made with anything but a console session.
 *
 * @throws BillingSessionRequiredException When the credential is a token.
 */
fun AuthenticatedPrincipal.requireBillingWrite() {
    if (!allowsBillingWrite()) throw BillingSessionRequiredException()
}
