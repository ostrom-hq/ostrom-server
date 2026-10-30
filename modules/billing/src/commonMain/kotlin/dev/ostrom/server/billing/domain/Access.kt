package dev.ostrom.server.billing.domain

import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
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
