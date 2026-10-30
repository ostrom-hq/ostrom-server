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

/** Buying, changing and cancelling the subscription; includes [BILLING_READ]. */
const val BILLING_MANAGE = "billing:manage"

/** The full scope of a member's interactive console session (identity module). */
private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a credential may use billing with [scope]: only a person (never a device, an agent session or a service),
 * whose token carries [scope], [BILLING_MANAGE] for [BILLING_READ], or the console session's full scope.
 */
fun AuthenticatedPrincipal.allowsBilling(scope: String): Boolean = kind == PrincipalKind.USER &&
    (scope in scopes || BILLING_MANAGE in scopes || ALL_USER_SCOPES in scopes)
