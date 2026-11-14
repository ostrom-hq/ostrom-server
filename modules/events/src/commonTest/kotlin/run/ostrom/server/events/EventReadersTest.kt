// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventsScopeException
import run.ostrom.server.events.domain.InvalidCursorException
import run.ostrom.server.events.domain.InvalidEventTypeException
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.presentation.DENIED_FRAME
import run.ostrom.server.events.presentation.cursorOf
import run.ostrom.server.events.presentation.eventFrame
import run.ostrom.server.events.presentation.gapFrame
import run.ostrom.server.events.presentation.readerOf
import run.ostrom.server.events.presentation.stillReads
import run.ostrom.server.events.presentation.toOrgEvent
import run.ostrom.server.events.presentation.typesOf
import run.ostrom.server.events.presentation.withLogin
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.NoOrganizationCredentialException
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.OrganizationNotFoundException
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who reads the feed and what they see, the cursor and the types they send, and the frames they get. */
class EventReadersTest {
    private val org = Uuid.random()
    private val mia = Uuid.random()

    /** Resolves every principal to [role], or to no one. */
    private class Resolver(var role: OrgRole?, val member: Uuid, val org: Uuid) : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            role?.let { OrgPrincipal(org, member, it) }
    }

    /** Says whether a person's credential is live. */
    private class Credentials(var live: Boolean) : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null

        override suspend fun isLive(subjectId: String, credentialId: String): Boolean = live
    }

    private fun user(scopes: Set<String>, credential: String? = "c1") =
        AuthenticatedPrincipal("u1", PrincipalKind.USER, scopes, credentialId = credential)

    private fun key(vararg scopes: String) = AuthenticatedPrincipal("k1", PrincipalKind.SERVICE, scopes.toSet())

    @Test
    fun aBrowserACloudTokenOrAKeyReadsAndAnAgentOrAnotherScopeDoesNot() = runTest {
        val members = Resolver(OrgRole.MEMBER, mia, org)
        val browser =
            AuthenticatedPrincipal("u1", PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)

        assertEquals(EventAudience(mia, false), members.readerOf(browser).second)
        assertEquals(EventAudience(mia, false), members.readerOf(user(setOf("cloud:write"))).second, "The CLI")
        assertEquals(EventAudience(mia, false), members.readerOf(key("cloud:read")).second)
        assertEquals(EventAudience(mia, true), members.readerOf(key("cloud:admin")).second)
        assertEquals(EventAudience(mia, true), members.readerOf(key("cloud:read", "team:read")).second)
        assertFailsWith<EventsScopeException> { members.readerOf(key("team:read")) }
        assertFailsWith<EventsScopeException> { members.readerOf(user(setOf("cloud:admin"))) }
        val agent = AuthenticatedPrincipal("lch_1", PrincipalKind.AGENT, setOf("agent:session"))
        assertFailsWith<EventsScopeException> { members.readerOf(agent) }
    }

    @Test
    fun theReadersLoginComesFromTheMembersOfTheirOrganizationWhenOneIsBound() = runTest {
        val audience = EventAudience(mia, false)
        val logins = object : MemberLogins {
            override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

            override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
                if (orgId == org) mapOf(mia to "mia") else emptyMap()
        }
        assertEquals(EventAudience(mia, false, "mia"), audience.withLogin(org, logins))
        assertEquals(audience, audience.withLogin(Uuid.random(), logins), "Not a member of that organization")
        assertEquals(audience, audience.withLogin(org, null), "No logins bound: none")
    }

    @Test
    fun anOwnerOrAnAdminSeesTheWholeOrganizationAndNobodyOutsideOneReads() = runTest {
        val reader = user(setOf("cloud:read"))
        assertEquals(EventAudience(mia, true), Resolver(OrgRole.OWNER, mia, org).readerOf(reader).second)
        assertEquals(EventAudience(mia, true), Resolver(OrgRole.ADMIN, mia, org).readerOf(reader).second)
        assertEquals(EventAudience(mia, false), Resolver(OrgRole.APPROVER, mia, org).readerOf(reader).second)
        val nobody = Resolver(null, mia, org)
        assertFailsWith<OrganizationNotFoundException> { nobody.readerOf(user(setOf("cloud:read"))) }
        assertFailsWith<NoOrganizationCredentialException> { nobody.readerOf(key("cloud:read")) }
    }

    @Test
    fun anOpenStreamStopsWhenItsCredentialOrItsMembershipOrItsRoleIsGone() = runTest {
        val resolver = Resolver(OrgRole.ADMIN, mia, org)
        val credentials = Credentials(live = true)
        val principal = user(setOf("cloud:read"))
        val (member, audience) = resolver.readerOf(principal)

        assertTrue(resolver.stillReads(credentials, principal, member, audience))
        credentials.live = false
        assertFalse(resolver.stillReads(credentials, principal, member, audience), "Signed out")
        assertTrue(resolver.stillReads(credentials, user(setOf("cloud:read"), credential = null), member, audience))
        assertTrue(resolver.stillReads(credentials, key("cloud:admin"), member, audience), "A key is resolved again")
        credentials.live = true
        resolver.role = OrgRole.MEMBER
        assertFalse(resolver.stillReads(credentials, principal, member, audience), "No longer an admin")
        assertTrue(resolver.stillReads(credentials, principal, member, EventAudience(mia, false)))
        resolver.role = null
        assertFalse(resolver.stillReads(credentials, principal, member, audience), "No longer a member")
        val other = Resolver(OrgRole.ADMIN, Uuid.random(), org)
        assertFalse(other.stillReads(credentials, principal, member, audience), "Another member now")
    }

    @Test
    fun theHeaderWinsOverTheQueryAndACursorIsANonNegativeNumber() {
        assertNull(cursorOf(null, null))
        assertEquals(7L, cursorOf("7", "3"))
        assertEquals(3L, cursorOf(null, " 3 "))
        assertEquals(0L, cursorOf(null, "0"))
        assertFailsWith<InvalidCursorException> { cursorOf("-1", null) }
        assertFailsWith<InvalidCursorException> { cursorOf("seven", null) }
    }

    @Test
    fun typesAreKnownNamesAndAtMostSixtyFour() {
        assertNull(typesOf(null))
        assertNull(typesOf(" , "))
        assertEquals(setOf("cloud.todo", "cloud.wait.created"), typesOf("cloud.todo, cloud.wait.created,cloud.todo"))
        assertFailsWith<InvalidEventTypeException> { typesOf("cloud.todo,cloud.nothing") }
        assertFailsWith<InvalidEventTypeException> { typesOf((0..64).joinToString(",") { "cloud.todo$it" }) }
    }

    @Test
    fun anEventIsFramedWithItsSequenceAndTypeAndUnreadableDataBecomesAnEmptyObject() {
        val at = Instant.parse("2026-11-13T10:00:00Z")
        val event = JournalEvent(4, "cloud.todo", "session", "s1", mia, at, """{"done":1}""")

        assertEquals(
            "id: 4\nevent: cloud.todo\ndata: {\"seq\":4,\"type\":\"cloud.todo\",\"subject\":\"session\"," +
                "\"subjectId\":\"s1\",\"memberId\":\"$mia\",\"at\":\"2026-11-13T10:00:00Z\",\"data\":{\"done\":1}}\n\n",
            eventFrame(event),
        )
        assertNull(event.copy(memberId = null).toOrgEvent().memberId)
        assertEquals(emptyMap(), event.copy(data = "[1]").toOrgEvent().data)
        assertEquals(emptyMap(), event.copy(data = "not json").toOrgEvent().data)
        assertEquals("event: gap\ndata: {\"oldestSeq\":6}\n\n", gapFrame(6))
        assertEquals("event: denied\ndata: {}\n\n", DENIED_FRAME)
    }
}
