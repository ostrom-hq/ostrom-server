// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernancePreview
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.People
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Red stub: the held member writes are not made yet. */
class OrgsGovernanceChange(
    @Suppress("unused") private val members: MembersRepository,
    @Suppress("unused") private val people: People,
    @Suppress("unused") private val commands: MemberCommands,
    @Suppress("unused") private val invitations: InvitationCommands,
) : GovernanceChange {
    override val kinds: Set<String> = setOf(MEMBER_ROLE, MEMBER_REMOVE, MEMBER_INVITE, MEMBER_LINK)

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview =
        GovernancePreview("", null, null)

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? = null

    /** The kinds and their payloads. */
    companion object {
        /** `PATCH /orgs/{org}/members/{login}`. */
        const val MEMBER_ROLE: String = "members.role"

        /** `DELETE /orgs/{org}/members/{login}`. */
        const val MEMBER_REMOVE: String = "members.remove"

        /** `POST /orgs/{org}/members/invites`. */
        const val MEMBER_INVITE: String = "members.invite"

        /** `POST /orgs/{org}/invitations/links`. */
        const val MEMBER_LINK: String = "members.link"

        /** @return A role change. */
        fun role(login: String, role: OrgRole): GovernanceWrite = GovernanceWrite(MEMBER_ROLE, JsonObject(emptyMap()))

        /** @return A removal. */
        fun removal(login: String): GovernanceWrite = GovernanceWrite(MEMBER_REMOVE, JsonObject(emptyMap()))

        /** @return Invitations by e-mail. */
        fun invites(invites: List<InviteRequest>): GovernanceWrite =
            GovernanceWrite(MEMBER_INVITE, JsonObject(emptyMap()))

        /** @return A link. */
        fun link(role: OrgRole, maxUses: Int): GovernanceWrite = GovernanceWrite(MEMBER_LINK, JsonObject(emptyMap()))
    }
}
