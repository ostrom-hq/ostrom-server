// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.uuid.Uuid

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

/**
 * Checks that a person may make a write on the members, without making it: a write held for a confirmation on the
 * console (contract audit 2, F1) is first checked as the person who asked, so that a confirmer's role never carries a
 * change the asker could not make, and a write that would fail opens no confirmation.
 */
interface CheckMemberWriteUseCase {
    /**
     * Checks [write] as [userId]'s.
     *
     * @param orgId The organization.
     * @param userId The person who asks.
     * @param write The write.
     * @throws OstromException With the refusal the write itself would get (400, 403, 404).
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, write: MemberWrite)
}

/**
 * Runs the checks of the use cases that make each write.
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 */
class CheckMemberWriteUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
) : CheckMemberWriteUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, write: MemberWrite) {
        val caller = requireMembership(orgs, members, orgId, userId)
        when (write) {
            is MemberWrite.Role ->
                requireRoleChange(caller, findMember(members, people, orgId, write.login), write.role)
            is MemberWrite.Removal -> requireRemoval(caller, findMember(members, people, orgId, write.login))
            is MemberWrite.Invites -> requireInvites(caller, write.invites)
            is MemberWrite.Link -> requireLink(caller, write.role, write.maxUses)
        }
    }
}
