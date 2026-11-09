// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports.domain

import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.uuid.Uuid

/**
 * Who reads a report, inside their organization.
 *
 * @property orgId The organization; nothing of another one is ever read.
 * @property memberId The member.
 * @property wholeOrganization Whether they see everyone's spending (owners, admins, billing), or only their own.
 */
data class Viewer(val orgId: Uuid, val memberId: Uuid, val wholeOrganization: Boolean) {
    /** Building a viewer. */
    companion object {
        private val ORG_WIDE = setOf(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING)

        /**
         * The viewer of a member.
         *
         * @param member The member the request acts for.
         * @return The viewer.
         */
        fun of(member: OrgPrincipal): Viewer = Viewer(member.orgId, member.memberId, member.role in ORG_WIDE)
    }
}
