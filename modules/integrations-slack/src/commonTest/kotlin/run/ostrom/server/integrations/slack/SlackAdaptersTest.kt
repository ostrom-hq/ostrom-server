// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.test.runTest
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.infrastructure.MemberSlackPeople
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A member as the Slack link reads them: their proven primary address only. */
class SlackAdaptersTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val org = Org(Uuid.random(), "acme", "Acme", null, null, now)
    private val user = User(Uuid.random(), "bob", "Bob", "Bob@Example.com", null, now)
    private val member = Member(Uuid.random(), org.id, user.id, OrgRole.ADMIN, now)

    private fun people(
        members: Member? = member,
        users: User? = user,
        emails: List<UserEmail> = listOf(UserEmail("bob@example.com", verified = true)),
        orgs: Org? = org,
    ) = MemberSlackPeople({ _, _ -> members }, { users }, { emails }, { orgs })

    @Test
    fun aMemberIsTheirLoginRoleProvenAddressAndOrganization() = runTest {
        assertEquals(
            SlackMember(member.id, "bob", OrgRole.ADMIN, "Bob@Example.com", "Acme"),
            people().member(org.id, member.id),
        )
        val unproven = people(emails = listOf(UserEmail("bob@example.com", verified = false)))
        assertNull(unproven.member(org.id, member.id)?.provenEmail)
        assertNull(people(users = user.copy(primaryEmail = null)).member(org.id, member.id)?.provenEmail)
    }

    @Test
    fun someoneWhoIsNoMemberAnyMoreIsNobody() = runTest {
        assertNull(people(members = null).member(org.id, member.id))
        assertNull(people(users = null).member(org.id, member.id))
        assertNull(people(orgs = null).member(org.id, member.id))
    }
}
