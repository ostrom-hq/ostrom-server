// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernancePreview
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.orgs.domain.CheckMemberWriteUseCase
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.orgs.domain.MemberProfile
import dev.ostrom.server.orgs.domain.MemberWrite
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
 * itself. The route checks the write as the asker's before holding it ([CheckMemberWriteUseCase]), so a confirmer's
 * role never carries a change the asker could not make. Confirmed, it is made as the confirmer's decision, by the
 * same use case as the console's, which checks the confirmer's role and records the change in the audit log under
 * their name.
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

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview =
        when (val asked = write.memberWrite()) {
            is MemberWrite.Role -> {
                val target = findMember(members, people, orgId, asked.login)
                val summary = "Change ${target.login}'s role to ${asked.role.key()}"
                GovernancePreview(summary, target.shown(), target.shown(asked.role))
            }
            is MemberWrite.Removal -> {
                val target = findMember(members, people, orgId, asked.login)
                GovernancePreview("Remove ${target.login} from the organization", target.shown(), null)
            }
            is MemberWrite.Invites -> {
                val summary = "Invite by e-mail: ${asked.invites.joinToString { it.email }}"
                GovernancePreview(summary, null, write.payload.getValue(INVITES))
            }
            is MemberWrite.Link -> GovernancePreview("Create an invitation link", null, write.payload)
        }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? {
        val userId = confirmer.principal.userId()
        return when (val asked = write.memberWrite()) {
            is MemberWrite.Role -> {
                val changed = commands.changeRole(orgId, userId, asked.login, asked.role)
                OstromJson.encodeToJsonElement(MemberResponse.Answer, changed.toMemberResponse())
            }
            is MemberWrite.Removal -> {
                commands.remove(orgId, userId, asked.login)
                null
            }
            is MemberWrite.Invites -> {
                val invited = invitations.byEmail(orgId, userId, asked.invites)
                OstromJson.encodeToJsonElement(InvitedResponse.serializer(), InvitedResponse(invited))
            }
            // Its address carries the link's secret: shown once, to the person who asked ([shownOnce]).
            is MemberWrite.Link -> {
                val issued = invitations.link(orgId, userId, asked.role, asked.maxUses)
                val response = InvitationLinkResponse(issued.invitation.toInvitationResponse(), issued.url)
                OstromJson.encodeToJsonElement(InvitationLinkResponse.serializer(), response)
            }
        }
    }

    /**
     * A link's address is shown once, to the person who asked, as the console shows a link it creates (decision of
     * 03/11/2026): then only the invitation is kept, marked `shown`, and an owner or an admin never reads the address.
     */
    override fun shownOnce(kind: String, result: JsonElement): JsonElement? =
        if (kind != MEMBER_LINK) {
            null
        } else {
            buildJsonObject {
                put(INVITATION, result.jsonObject.getValue(INVITATION))
                put(SHOWN, true)
            }
        }

    /** The kinds this change owns, and the payload of each. */
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
        private const val INVITATION = "invitation"
        private const val SHOWN = "shown"

        /**
         * The held write of [write], as the route asked for it.
         *
         * @param write The write on the members.
         *
         * @return The write the governance module holds.
         */
        fun of(write: MemberWrite): GovernanceWrite = when (write) {
            is MemberWrite.Role -> GovernanceWrite(
                MEMBER_ROLE,
                buildJsonObject {
                    put(LOGIN, write.login)
                    put(ROLE, write.role.key())
                },
            )
            is MemberWrite.Removal -> GovernanceWrite(MEMBER_REMOVE, buildJsonObject { put(LOGIN, write.login) })
            is MemberWrite.Invites -> GovernanceWrite(
                MEMBER_INVITE,
                buildJsonObject {
                    putJsonArray(INVITES) {
                        for (invite in write.invites) {
                            addJsonObject {
                                put(EMAIL, invite.email)
                                put(ROLE, invite.role.key())
                            }
                        }
                    }
                },
            )
            is MemberWrite.Link -> GovernanceWrite(
                MEMBER_LINK,
                buildJsonObject {
                    put(ROLE, write.role.key())
                    put(MAX_USES, write.maxUses)
                },
            )
        }

        // The write a held one stands for. The governance module only hands this change the kinds it owns.
        private fun GovernanceWrite.memberWrite(): MemberWrite = when (kind) {
            MEMBER_ROLE -> MemberWrite.Role(payload.login(), payload.role())
            MEMBER_REMOVE -> MemberWrite.Removal(payload.login())
            MEMBER_INVITE -> MemberWrite.Invites(
                payload.getValue(INVITES).jsonArray.map {
                    val invite = it.jsonObject
                    InviteRequest(invite.getValue(EMAIL).jsonPrimitive.content, invite.role())
                },
            )
            MEMBER_LINK -> MemberWrite.Link(payload.role(), payload.getValue(MAX_USES).jsonPrimitive.int)
            else -> error("No member write is named $kind.")
        }

        private fun JsonObject.login(): String = getValue(LOGIN).jsonPrimitive.content

        private fun JsonObject.role(): OrgRole = roleOf(getValue(ROLE).jsonPrimitive.content)

        // What a role change or a removal replaces, and what it writes: the membership's id (a member removed and
        // invited again is another membership, so a confirmation on the first goes stale), the login and the role,
        // never the address.
        private fun MemberProfile.shown(role: OrgRole = member.role): JsonObject = buildJsonObject {
            put("id", member.id.toString())
            put(LOGIN, login)
            put(ROLE, role.key())
        }
    }
}
