// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.auth.OrgRole

/** A write on the members, as a person asks for it. */
sealed interface MemberWrite {
    /**
     * Gives [login] the role [role].
     *
     * @property login The member.
     * @property role Their new role.
     */
    data class Role(val login: String, val role: OrgRole) : MemberWrite

    /**
     * Removes [login].
     *
     * @property login The member.
     */
    data class Removal(val login: String) : MemberWrite

    /**
     * Invites people by e-mail.
     *
     * @property invites Who, with which role.
     */
    data class Invites(val invites: List<InviteRequest>) : MemberWrite

    /**
     * Creates an invitation link.
     *
     * @property role The role it gives.
     * @property maxUses How many people it brings in.
     */
    data class Link(val role: OrgRole, val maxUses: Int) : MemberWrite
}
