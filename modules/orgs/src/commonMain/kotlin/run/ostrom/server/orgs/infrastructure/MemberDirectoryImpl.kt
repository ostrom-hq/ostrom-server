// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.orgs.domain.MembersRepository
import kotlin.uuid.Uuid

/**
 * [MemberDirectory] on the organizations' members: a member is active while its row exists (removing a member,
 * leaving or deleting the organization deletes it).
 *
 * @param members The members.
 */
class MemberDirectoryImpl(private val members: MembersRepository) : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean {
        val id = Uuid.parseOrNull(memberId) ?: return false
        return members.findById(orgId, id) != null
    }
}
