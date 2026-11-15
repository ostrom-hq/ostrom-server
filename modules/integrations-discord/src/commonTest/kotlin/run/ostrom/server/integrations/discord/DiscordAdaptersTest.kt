// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import kotlinx.coroutines.test.runTest
import run.ostrom.server.identity.domain.User
import run.ostrom.server.integrations.discord.domain.DiscordMember
import run.ostrom.server.integrations.discord.infrastructure.MemberDiscordPeople
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A member as the Discord link reads them: their login, role and organization. */
class DiscordAdaptersTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val org = Org(Uuid.random(), "acme", "Acme", null, null, now)
    private val user = User(Uuid.random(), "bob", "Bob", "bob@example.com", null, now)
    private val member = Member(Uuid.random(), org.id, user.id, OrgRole.ADMIN, now)

    private fun people(members: Member? = member, users: User? = user, orgs: Org? = org) =
        MemberDiscordPeople({ _, _ -> members }, { users }, { orgs })

    @Test
    fun aMemberIsTheirLoginRoleAndOrganization() = runTest {
        assertEquals(DiscordMember(member.id, "bob", OrgRole.ADMIN, "Acme"), people().member(org.id, member.id))
    }

    @Test
    fun someoneWhoIsNoMemberAnyMoreIsNobody() = runTest {
        assertNull(people(members = null).member(org.id, member.id))
        assertNull(people(users = null).member(org.id, member.id))
        assertNull(people(orgs = null).member(org.id, member.id))
    }
}
