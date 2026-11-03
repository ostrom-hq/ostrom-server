// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.MemberEmails
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.People
import kotlin.uuid.Uuid

/**
 * [MemberEmails] on the organizations' members and the identity module's people: the primary e-mail of a member of
 * the organization (row-level security hides every other organization's members), only when the person verified it.
 *
 * @param members The members.
 * @param people The people.
 */
class MemberEmailsImpl(private val members: MembersRepository, private val people: People) : MemberEmails {
    override suspend fun emailOf(orgId: Uuid, memberId: Uuid): String? {
        val member = members.findById(orgId, memberId) ?: return null
        val email = people.get(member.userId)?.email ?: return null
        return email.takeIf { it.lowercase() in people.verifiedEmails(member.userId) }
    }
}
