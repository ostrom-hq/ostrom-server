// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.domain

import kotlin.uuid.Uuid

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
     * @return The write as it will be made: the invitations' addresses cleaned, each once.
     * @throws OstromException With the refusal the write itself would get (400, 403, 404).
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, write: MemberWrite): MemberWrite
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
    override suspend fun invoke(orgId: Uuid, userId: Uuid, write: MemberWrite): MemberWrite {
        val caller = requireMembership(orgs, members, orgId, userId)
        return when (write) {
            is MemberWrite.Role -> write.also {
                requireRoleChange(caller, findMember(members, people, orgId, it.login), it.role)
            }
            is MemberWrite.Removal -> write.also {
                requireRemoval(caller, findMember(members, people, orgId, it.login))
            }
            is MemberWrite.Invites -> MemberWrite.Invites(requireInvites(caller, write.invites))
            is MemberWrite.Link -> write.also { requireLink(caller, it.role, it.maxUses) }
        }
    }
}
