// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.auth.MemberLogins
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.People
import kotlin.uuid.Uuid

/**
 * [MemberLogins] on the organizations' members and the identity module's people: a login names a member while the
 * person is a member of the organization (row-level security hides every other organization's members). Either way,
 * two reads: the organization's members, then their people at once.
 *
 * @param members The members.
 * @param people The people.
 */
class MemberLoginsImpl(private val members: MembersRepository, private val people: People) : MemberLogins {
    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> {
        if (logins.isEmpty()) return emptyMap()
        // A login is any case (People.findByLogin).
        val byLogin = named(orgId, null).entries.associate { (id, login) -> login.lowercase() to id }
        return logins.mapNotNull { login -> byLogin[login.lowercase()]?.let { login to it } }.toMap()
    }

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
        if (memberIds.isEmpty()) emptyMap() else named(orgId, memberIds.toSet())

    /** The login of every member of [orgId] ([wanted] only, unless null) whose person is known. */
    private suspend fun named(orgId: Uuid, wanted: Set<Uuid>?): Map<Uuid, String> {
        val found = members.list(orgId).filter { wanted == null || it.id in wanted }
        val logins = people.getMany(found.map { it.userId }).associate { it.id to it.login }
        return found.mapNotNull { member -> logins[member.userId]?.let { member.id to it } }.toMap()
    }
}
