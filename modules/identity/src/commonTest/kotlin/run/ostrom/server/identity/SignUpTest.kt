// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.BootstrapInstanceUseCaseImpl
import run.ostrom.server.identity.domain.GrantSignUpUseCaseImpl
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.RateLimitedException
import run.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import run.ostrom.server.identity.domain.SignUpClosedException
import run.ostrom.server.identity.domain.SignUpGrant
import run.ostrom.server.identity.domain.SignUpGrantNotFoundException
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.SignUpPolicyImpl
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.FakeSignUpInvitations
import run.ostrom.server.identity.fakes.InMemorySetupLinks
import run.ostrom.server.identity.fakes.InMemoryUsersRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.IdentityInstanceBootstrapper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** TC-CODE-AUTH-2: an invite-only instance, its setup link and who may get an account or create organizations. */
class SignUpTest {
    private val clock = SettableClock()
    private val users = InMemoryUsersRepository()
    private val links = InMemorySetupLinks(users)
    private val invitations = FakeSignUpInvitations(mutableSetOf("invite-token"))
    private val hasher = FakeHasher()
    private val invite = SignUpPolicyImpl(SignUpMode.INVITE, links, invitations, hasher, clock)
    private val bootstrap = BootstrapInstanceUseCaseImpl(links, CountingSecretGenerator(), hasher, clock)
    private val github = VerifiedIdentity(IdentityProvider.GITHUB, "583231", "ada@example.com", true, login = "ada")

    @Test
    fun theModeIsInviteUnlessTheOperatorOpensIt() {
        assertEquals(SignUpMode.INVITE, SignUpMode.of(null))
        assertEquals(SignUpMode.OPEN, SignUpMode.of(" Open "))
        assertEquals(SignUpMode.INVITE, SignUpMode.of("invite"))
        assertNull(SignUpMode.of("everyone"))
    }

    @Test
    fun theSetupLinkWorksOnceForAnHourAndOnlyBeforeTheFirstAccount() = runTest {
        val first = assertNotNull(bootstrap())
        assertEquals("osb_secret1", first.token)
        assertEquals(clock.now + 1.hours, first.link.expiresAt)
        val second = assertNotNull(bootstrap())
        assertFalse(invite.accepts(first.token), "A new link stops the earlier one")
        assertTrue(invite.accepts(second.token))

        val ada = Uuid.random()
        assertTrue(invite.admits(github, SignUpGrant.Presented(second.token)))
        assertTrue(invite.accepts(second.token), "Not spent until the account exists")
        invite.claim(SignUpGrant.Presented(second.token), ada)
        assertFalse(invite.accepts(second.token), "Used once")
        assertFalse(invite.admits(github, SignUpGrant.Presented(second.token)))
        assertTrue(invite.mayCreateOrgs(ada), "The setup link's person creates the organizations")
        assertFalse(invite.mayCreateOrgs(Uuid.random()))

        val third = assertNotNull(bootstrap())
        clock.now += 61.minutes
        assertFalse(invite.accepts(third.token), "Expired after an hour")

        users.users[ada] = User(ada, "ada", "Ada", null, null, clock.now)
        assertNull(bootstrap(), "Once someone signed up, owners invite people instead")
    }

    @Test
    fun anInviteOnlyInstanceAdmitsAnInvitationTheSsoAndTheOperatorOnly() = runTest {
        val id = Uuid.random()
        assertTrue(invite.admits(github, SignUpGrant.Presented("invite-token")))
        assertTrue(invite.accepts("invite-token"))
        assertFalse(invite.accepts("revoked-token"))
        assertFalse(invite.admits(github, SignUpGrant.Presented("revoked-token")))
        assertFalse(invite.admits(github, SignUpGrant.Presented(null)))
        assertFalse(invite.admits(github, SignUpGrant.Presented("")))
        assertFalse(invite.admits(github, SignUpGrant.Presented("x".repeat(257))))
        assertFalse(invite.accepts(""))
        assertFalse(invite.accepts("osb_unknown"))
        assertTrue(invite.admits(github, SignUpGrant.Operator))
        val sso = VerifiedIdentity(IdentityProvider.OIDC, "sub", null, false, connectionId = Uuid.random())
        assertTrue(invite.admits(sso, SignUpGrant.Presented(null)), "The organization's SSO provisions")
        assertFalse(invite.mayCreateOrgs(id))

        // Only a setup link is spent by the account it creates: neither an invitation nor the operator.
        invite.claim(SignUpGrant.Presented("invite-token"), id)
        invite.claim(SignUpGrant.Presented(null), id)
        invite.claim(SignUpGrant.Operator, id)
        assertTrue(invite.accepts("invite-token"))
        assertFalse(invite.mayCreateOrgs(id))

        val withoutOrgs = SignUpPolicyImpl(SignUpMode.INVITE, links, null, hasher, clock)
        assertFalse(withoutOrgs.admits(github, SignUpGrant.Presented("invite-token")))
        assertFalse(withoutOrgs.accepts("invite-token"))
    }

    @Test
    fun anOpenInstanceAdmitsEveryoneAndLetsEveryoneCreateOrganizations() = runTest {
        val open = SignUpPolicyImpl(SignUpMode.OPEN, links, invitations, hasher, clock)

        assertTrue(open.admits(github, SignUpGrant.Presented(null)))
        assertTrue(open.mayCreateOrgs(Uuid.random()))
    }

    @Test
    fun aNewAccountIsRefusedOnAnInviteOnlyInstanceButAKnownPersonSignsIn() = runTest {
        val resolve = ResolveUserUseCaseImpl(users, invite, RecordingAuditLog(), clock)

        val refused = assertFailsWith<SignUpClosedException> { resolve(github) }
        assertEquals("signup_closed", refused.type)
        assertTrue(users.users.isEmpty(), "Nothing is created")

        val ada = resolve(github, SignUpGrant.Presented("invite-token"))
        assertEquals("ada", ada.login)
        assertEquals(ada, resolve(github), "Once created, the account signs in without a grant")
    }

    @Test
    fun theGrantIsCheckedAndRateLimitedPerAddress() = runTest {
        val limiter = ScriptedRateLimiter()
        val grant = GrantSignUpUseCaseImpl(invite, limiter)

        grant("invite-token", "1.2.3.4")
        assertEquals(listOf("signup-grant:1.2.3.4"), limiter.keys)
        assertFailsWith<SignUpGrantNotFoundException> { grant("unknown", "1.2.3.4") }
        limiter.allow = false
        assertFailsWith<RateLimitedException> { grant("invite-token", "1.2.3.4") }
    }

    @Test
    fun theBootstrapCommandGetsTheConsoleSetupPage() = runTest {
        val bootstrapper = IdentityInstanceBootstrapper(bootstrap) { "https://app.example.com/setup#token=$it" }

        val output = assertNotNull(bootstrapper.setupLink())

        assertEquals("https://app.example.com/setup#token=osb_secret1", output.url)
        assertEquals("2026-10-30T11:00:00Z", output.expiresAt)
        assertEquals(1, links.links.size, "One link stored, by its hash")
        assertTrue("h(osb_secret1)" in links.links)
        val ada = Uuid.random()
        users.users[ada] = User(ada, "ada", "Ada", null, null, clock.now)
        assertNull(bootstrapper.setupLink(), "No link once the instance has an account")
    }
}
