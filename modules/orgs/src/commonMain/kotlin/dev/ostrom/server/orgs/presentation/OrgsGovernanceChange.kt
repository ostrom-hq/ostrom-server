// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernancePreview
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.orgs.domain.InvalidOrgRequestException
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.orgs.domain.MemberProfile
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.People
import dev.ostrom.server.orgs.domain.findMember
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.uuid.Uuid

/**
 * The writes on the members made with a person's API token, held for a confirmation on the console as the rules' writes
 * are (contract audit 2, F1): changing a role, removing a member, inviting by e-mail and creating an invitation link.
 * A token sits on a developer's machine, where an agent can present it: none of these writes is made by the token
 * itself. Confirmed, each is made as the confirmer's decision, by the same use case as the console's, which checks
 * the confirmer's role and records the change in the audit log under their name.
 *
 * @param members Reads the member a role change or a removal names, for the preview.
 * @param people Finds them by login.
 * @param commands Changes a role, removes a member.
 * @param invitations Invites by e-mail, creates a link.
 */
class OrgsGovernanceChange(
    private val members: MembersRepository,
    private val people: People,
    private val commands: MemberCommands,
    private val invitations: InvitationCommands,
) : GovernanceChange {
    override val kinds: Set<String> = setOf(MEMBER_ROLE, MEMBER_REMOVE, MEMBER_INVITE, MEMBER_LINK)

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview {
        val payload = write.payload
        return when (write.kind) {
            MEMBER_ROLE -> {
                val role = payload.role()
                if (role == OrgRole.OWNER) {
                    throw InvalidOrgRequestException("Transfer the ownership to make someone owner.")
                }
                val target = findMember(members, people, orgId, payload.login())
                GovernancePreview("Change ${target.login}'s role to ${role.key()}", target.shown(), target.shown(role))
            }
            MEMBER_REMOVE -> {
                val target = findMember(members, people, orgId, payload.login())
                GovernancePreview("Remove ${target.login} from the organization", target.shown(), null)
            }
            MEMBER_INVITE -> {
                val invites = payload.getValue(INVITES)
                GovernancePreview("Invite ${invites.jsonArray.size} people by e-mail", null, invites)
            }
            // MEMBER_LINK: the governance module only hands this change the kinds it owns.
            else -> GovernancePreview("Create an invitation link", null, payload)
        }
    }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? {
        val userId = confirmer.principal.userId()
        val payload = write.payload
        return when (write.kind) {
            MEMBER_ROLE -> {
                val changed = commands.changeRole(orgId, userId, payload.login(), payload.role())
                OstromJson.encodeToJsonElement(MemberResponse.Answer, changed.toMemberResponse())
            }
            MEMBER_REMOVE -> {
                commands.remove(orgId, userId, payload.login())
                null
            }
            MEMBER_INVITE -> {
                val invited = invitations.byEmail(orgId, userId, payload.invites())
                OstromJson.encodeToJsonElement(InvitedResponse.serializer(), InvitedResponse(invited))
            }
            // MEMBER_LINK. Its address carries the link's secret: the confirmation keeps it as its result, which only
            // the person who asked and the organization's owners and admins read, as they would read the 201.
            else -> {
                val maxUses = payload.getValue(MAX_USES).jsonPrimitive.int
                val issued = invitations.link(orgId, userId, payload.role(), maxUses)
                val response = InvitationLinkResponse(issued.invitation.toInvitationResponse(), issued.url)
                OstromJson.encodeToJsonElement(InvitationLinkResponse.serializer(), response)
            }
        }
    }

    /** The kinds this change owns, and the payload each route holds. */
    companion object {
        /** `PATCH /orgs/{org}/members/{login}` (contract `GovernanceChangeKind`). */
        const val MEMBER_ROLE: String = "members.role"

        /** `DELETE /orgs/{org}/members/{login}`. */
        const val MEMBER_REMOVE: String = "members.remove"

        /** `POST /orgs/{org}/members/invites`. */
        const val MEMBER_INVITE: String = "members.invite"

        /** `POST /orgs/{org}/invitations/links`. */
        const val MEMBER_LINK: String = "members.link"

        private const val LOGIN = "login"
        private const val ROLE = "role"
        private const val EMAIL = "email"
        private const val INVITES = "invites"
        private const val MAX_USES = "maxUses"

        /**
         * A role change.
         *
         * @param login The member.
         * @param role Their new role.
         *
         * @return The held write.
         */
        fun role(login: String, role: OrgRole): GovernanceWrite = GovernanceWrite(
            MEMBER_ROLE,
            buildJsonObject {
                put(LOGIN, login)
                put(ROLE, role.key())
            },
        )

        /**
         * A removal.
         *
         * @param login The member.
         *
         * @return The held write.
         */
        fun removal(login: String): GovernanceWrite =
            GovernanceWrite(MEMBER_REMOVE, buildJsonObject { put(LOGIN, login) })

        /**
         * Invitations by e-mail.
         *
         * @param invites Who, with which role.
         *
         * @return The held write.
         */
        fun invites(invites: List<InviteRequest>): GovernanceWrite = GovernanceWrite(
            MEMBER_INVITE,
            buildJsonObject {
                putJsonArray(INVITES) {
                    for (invite in invites) {
                        addJsonObject {
                            put(EMAIL, invite.email)
                            put(ROLE, invite.role.key())
                        }
                    }
                }
            },
        )

        /**
         * An invitation link.
         *
         * @param role The role it gives.
         * @param maxUses How many people it brings in.
         *
         * @return The held write.
         */
        fun link(role: OrgRole, maxUses: Int): GovernanceWrite = GovernanceWrite(
            MEMBER_LINK,
            buildJsonObject {
                put(ROLE, role.key())
                put(MAX_USES, maxUses)
            },
        )

        private fun JsonObject.login(): String = getValue(LOGIN).jsonPrimitive.content

        private fun JsonObject.role(): OrgRole = roleOf(getValue(ROLE).jsonPrimitive.content)

        private fun JsonObject.invites(): List<InviteRequest> = getValue(INVITES).jsonArray.map {
            val invite = it.jsonObject
            InviteRequest(invite.getValue(EMAIL).jsonPrimitive.content, invite.role())
        }

        // What a role change or a removal replaces, and what it writes: the login and the role, never the address.
        private fun MemberProfile.shown(role: OrgRole = member.role): JsonObject = buildJsonObject {
            put(LOGIN, login)
            put(ROLE, role.key())
        }
    }
}
