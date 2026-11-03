// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.MemberLogins
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.People
import kotlin.uuid.Uuid

/**
 * [MemberLogins] on the organizations' members and the identity module's people: a login names a member while the
 * person is a member of the organization (row-level security hides every other organization's members).
 *
 * @param members The members.
 * @param people The people.
 */
class MemberLoginsImpl(private val members: MembersRepository, private val people: People) : MemberLogins {
    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
        logins.distinct().mapNotNull { login ->
            people.findByLogin(login)?.let { members.findByUser(orgId, it.id) }?.let { login to it.id }
        }.toMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> {
        val wanted = memberIds.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val found = members.list(orgId).filter { it.id in wanted }
        val logins = people.getMany(found.map { it.userId }).associate { it.id to it.login }
        return found.mapNotNull { member -> logins[member.userId]?.let { member.id to it } }.toMap()
    }
}
