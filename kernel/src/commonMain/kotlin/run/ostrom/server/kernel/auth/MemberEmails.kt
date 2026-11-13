// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * The address a member of an organization is written to. The orgs module binds it; billing asks it for the person who
 * opens a Checkout, so Stripe's customer carries their address. Unbound, each module keeps its own default (no
 * address).
 */
interface MemberEmails {
    /**
     * The verified primary e-mail of a member.
     *
     * @param orgId The organization.
     * @param memberId The member's id, as [OrgPrincipal.memberId] gives it.
     *
     * @return The person's primary e-mail when it is one of their verified addresses; null for a member of another
     *   organization, an unknown member, or a person without a verified primary e-mail.
     */
    suspend fun emailOf(orgId: Uuid, memberId: Uuid): String?
}
