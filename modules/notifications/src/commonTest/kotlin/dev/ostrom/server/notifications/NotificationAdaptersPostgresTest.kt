// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.NotificationPreferences
import dev.ostrom.server.identity.domain.NotificationPreferencesRepository
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.WebSession
import dev.ostrom.server.identity.domain.WebSessionsRepository
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.notifications.domain.Approver
import dev.ostrom.server.notifications.domain.ApproverNotificationsChannel
import dev.ostrom.server.notifications.domain.Approvers
import dev.ostrom.server.notifications.domain.LiveCredentials
import dev.ostrom.server.notifications.domain.PersonOrganizations
import dev.ostrom.server.notifications.domain.PushCredential
import dev.ostrom.server.notifications.domain.PushSender
import dev.ostrom.server.notifications.infrastructure.ExpoPushSender
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The notifications' reads of the identity and orgs modules on PostgreSQL: who an approver is in an organization,
 * whether a phone's credential still works, and the organizations of a person.
 */
class NotificationAdaptersPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, NotificationsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.user(login: String, email: String?, verified: Boolean): User {
        val user = User(Uuid.random(), login, login, email, null, now)
        val identity = VerifiedIdentity(IdentityProvider.GITHUB, "gh-$login", email, verified, login = login)
        koin.get<UsersRepository>().create(user, identity, email?.let { UserEmail(it, verified) }, now)
        return user
    }

    private suspend fun TestServer.org(owner: User): Pair<Org, Member> {
        val org = Org(Uuid.random(), "team-${owner.login}", "Team ${owner.login}", null, null, now)
        val member = Member(Uuid.random(), org.id, owner.id, OrgRole.MEMBER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        return org to member
    }

    @Test
    fun anApproverIsAMemberReachedAtTheirProvenAddressAndOnTheirPhoneUnlessTheyTurnedRequestsOff() =
        serverTest(modules) { server ->
            val approvers = server.koin.get<Approvers>()
            val bob = server.user("bob-n", "bob@example.com", verified = true)
            val (org, member) = server.org(bob)

            val reached = approvers.reach(org.id, "BOB-N")
            assertEquals(Approver(bob.id, member.id, "bob@example.com", pushes = true), reached)
            server.koin.get<NotificationPreferencesRepository>()
                .put(bob.id, NotificationPreferences(requests = false, decisions = true, spending = false), now)
            assertEquals(false, approvers.reach(org.id, "bob-n")?.pushes, "Requests turned off on the phone")

            val unproven = server.user("eve-n", "eve@example.com", verified = false)
            val (eveOrg, _) = server.org(unproven)
            assertNull(approvers.reach(eveOrg.id, "eve-n")?.email, "An address nobody proved is never written to")
            val silent = server.user("sam-n", email = null, verified = false)
            val (samOrg, _) = server.org(silent)
            assertNull(approvers.reach(samOrg.id, "sam-n")?.email)

            assertNull(approvers.reach(org.id, "nobody-n"), "No such person")
            assertNull(approvers.reach(eveOrg.id, "bob-n"), "Not a member of that organization")
        }

    @Test
    fun aPhonesCredentialIsLiveUntilRevokedOrExpiredAndABrowserSessionWhileItLasts() = serverTest(modules) { server ->
        val credentials = server.koin.get<LiveCredentials>()
        val tokens = server.koin.get<ApiTokensRepository>()
        val ann = server.user("ann-n", "ann@example.com", verified = true)
        fun token(expiresAt: Instant?) = ApiToken(
            Uuid.random(), ann.id, null, "app", TokenKind.MOBILE, emptySet(), "hint", null, now, expiresAt, null, null,
        )
        suspend fun live(id: Uuid, browser: Boolean = false) =
            credentials.live(ann.id, PushCredential(ann.id, id, browser))

        val app = token(now + 1.hours).also { tokens.create(it, "hash-app") }
        val forever = token(null).also { tokens.create(it, "hash-forever") }
        val expired = token(now - 1.hours).also { tokens.create(it, "hash-expired") }
        val revoked = token(now + 1.hours).also { tokens.create(it, "hash-revoked") }
        tokens.revoke(ann.id, revoked.id, now)

        assertTrue(live(app.id))
        assertTrue(live(forever.id))
        assertFalse(live(expired.id))
        assertFalse(live(revoked.id))
        assertFalse(live(Uuid.random()), "Unknown")

        val sessions = server.koin.get<WebSessionsRepository>()
        val session = WebSession(Uuid.random(), ann.id, now, now + 1.hours, now, null)
        sessions.create(session, "hash-session")
        assertTrue(live(session.id, browser = true))
        sessions.revoke(session.id, now)
        assertFalse(live(session.id, browser = true))
    }

    @Test
    fun theServerSendsThroughExpoAndTellsApproversAsAGateChannel() = serverTest(modules) { server ->
        assertTrue(server.koin.get<PushSender>() is ExpoPushSender)
        val channel = server.koin.getAll<GateChannel>().single()
        assertTrue(channel is ApproverNotificationsChannel)
        assertEquals("notifications", channel.name)
    }

    @Test
    fun thePersonsOrganizationsAreTheirMemberships() = serverTest(modules) { server ->
        val kim = server.user("kim-n", "kim@example.com", verified = true)
        val (first, _) = server.org(kim)
        server.org(server.user("lou-n", null, verified = false))

        assertEquals(listOf(first.id), server.koin.get<PersonOrganizations>().of(kim.id))
    }
}
