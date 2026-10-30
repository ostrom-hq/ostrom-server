// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.MemberDirectory
import dev.ostrom.server.orgs.domain.MembersRepository
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
