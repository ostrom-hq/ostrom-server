package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AccountLinkRequiredException
import dev.ostrom.server.identity.domain.DuplicateIdentityException
import dev.ostrom.server.identity.domain.EndWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.GetProfileUseCaseImpl
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IdentityTakenException
import dev.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import dev.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import dev.ostrom.server.identity.domain.SESSION_LIFETIME
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UserNotFoundException
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.loginOf
import dev.ostrom.server.identity.domain.nowMillis
import dev.ostrom.server.identity.domain.vouchesFor
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryUsersRepository
import dev.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.SettableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AccountUseCasesTest {
    private val users = InMemoryUsersRepository()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val resolve = ResolveUserUseCaseImpl(users, audit, clock)

    private fun github(subject: String, email: String?, verified: Boolean = true, name: String? = "Ada") =
        VerifiedIdentity(IdentityProvider.GITHUB, subject, email, verified, name, "https://avatars.example/a.png")

    private fun google(subject: String, email: String?, verified: Boolean = true, hostedDomain: String? = null) =
        VerifiedIdentity(IdentityProvider.GOOGLE, subject, email, verified, hostedDomain = hostedDomain)

    private fun sso(subject: String, email: String?) =
        VerifiedIdentity(IdentityProvider.OIDC, subject, email, true, "Mallory", connectionId = Uuid.random())

    @Test
    fun aFirstSignInCreatesTheUserWithItsVerifiedEmail() = runTest {
        val user = resolve(github("1", " Ada@Example.COM "))

        assertEquals("Ada", user.displayName)
        assertEquals("ada@example.com", user.primaryEmail)
        assertEquals("https://avatars.example/a.png", user.avatarUrl)
        assertEquals(listOf(UserEmail("ada@example.com", true)), users.listEmails(user.id))
        assertEquals(listOf("user.created"), audit.entries.map { it.action })
    }

    @Test
    fun theSameIdentitySignsInToTheSameUser() = runTest {
        val first = resolve(github("1", "ada@example.com"))
        val second = resolve(github("1", "ada@example.com"))

        assertEquals(first.id, second.id)
        assertEquals(1, users.users.size)
    }

    @Test
    fun aVerifiedEmailMergesAnotherProviderIntoTheSameUser() = runTest {
        val first = resolve(github("1", "ada@gmail.com"))
        val second = resolve(google("g-1", "ADA@gmail.com"))

        assertEquals(first.id, second.id)
        assertEquals(listOf("github", "google"), users.listIssuers(first.id))
        assertEquals(listOf("user.created", "identity.linked"), audit.entries.map { it.action })
    }

    @Test
    fun anUnverifiedEmailNeverMergesAccounts() = runTest {
        val owner = resolve(github("1", "ada@example.com"))
        val other = resolve(google("g-2", "ada@example.com", verified = false))

        assertNotEquals(owner.id, other.id)
        assertEquals(listOf(UserEmail("ada@example.com", false)), users.listEmails(other.id))
    }

    @Test
    fun aKnownIdentityDoesNotTakeAnEmailAnotherUserVerified() = runTest {
        val ada = resolve(github("1", "ada@gmail.com"))
        val bob = resolve(google("g-2", "bob@gmail.com"))
        // Bob's Google account now reports Ada's verified address: Bob keeps his account, Ada keeps her address.
        val again = resolve(google("g-2", "ada@gmail.com"))

        assertEquals(bob.id, again.id)
        assertEquals(listOf(UserEmail("bob@gmail.com", true)), users.listEmails(bob.id))
        assertEquals(listOf(UserEmail("ada@gmail.com", true)), users.listEmails(ada.id))
    }

    @Test
    fun aKnownIdentityRecordsANewAddress() = runTest {
        val ada = resolve(github("1", null))
        resolve(github("1", "ada@example.com"))
        resolve(github("1", "ada@example.com"))

        assertEquals(listOf(UserEmail("ada@example.com", true)), users.listEmails(ada.id))
    }

    @Test
    fun anInvalidProviderEmailIsIgnoredAndTheNameFallsBack() = runTest {
        val user = resolve(VerifiedIdentity(IdentityProvider.OIDC, "s", "not-an-address", true, "  ", "http://insecure"))

        assertNull(user.primaryEmail)
        assertNull(user.avatarUrl)
        assertEquals("Ostrom user", user.displayName)
    }

    @Test
    fun theNameFallsBackToTheEmailAndIsCut() = runTest {
        assertEquals("ada", resolve(github("1", "ada@example.com", name = null)).displayName)
        assertEquals(100, resolve(github("2", null, name = "x".repeat(300))).displayName.length)
    }

    @Test
    fun aTooLongAvatarUrlIsDropped() = runTest {
        val user = resolve(VerifiedIdentity(IdentityProvider.GITHUB, "3", null, false, "A", "https://" + "a".repeat(3000)))

        assertNull(user.avatarUrl)
    }

    @Test
    fun aLostCreationRaceIsResolvedBySigningInAgain() = runTest {
        val winner = Uuid.random()
        users.duplicatesToThrow = 1
        users.onDuplicate = {
            // The other sign-in created the user meanwhile.
            users.users[winner] = dev.ostrom.server.identity.domain.User(winner, "Ada", null, null, clock.now)
            users.identities["github" to "1"] = winner
        }

        assertEquals(winner, resolve(github("1", null)).id)
    }

    @Test
    fun aSecondLostRaceIsReported() = runTest {
        users.duplicatesToThrow = 2

        assertFailsWith<DuplicateIdentityException> { resolve(github("1", null)) }
    }

    @Test
    fun anOrganizationSsoCannotTakeOverAnAccountByAssertingItsAddress() = runTest {
        // S2-07: the admin of an org's IdP types the victim's address, "verified": nobody is signed in.
        val victim = resolve(github("1", "victim@gmail.com"))

        assertFailsWith<AccountLinkRequiredException> { resolve(sso("evil-1", "Victim@Gmail.com")) }
        assertEquals(listOf("github"), users.listIssuers(victim.id))
        assertEquals(1, users.users.size)
        assertEquals(listOf("user.created"), audit.entries.map { it.action })
    }

    @Test
    fun anAddressAnSsoAssertsIsNeverAnAnchorALaterSignInMergesInto() = runTest {
        // S2-07, pre-hijack: the SSO signs up first with the victim's address; the victim's own sign-in stays apart.
        val mallory = resolve(sso("evil-2", "victim@example.com"))
        assertEquals(listOf(UserEmail("victim@example.com", false)), users.listEmails(mallory.id))

        val victim = resolve(VerifiedIdentity(IdentityProvider.EMAIL, "victim@example.com", "victim@example.com", true))

        assertNotEquals(mallory.id, victim.id)
        assertEquals(listOf(UserEmail("victim@example.com", true)), users.listEmails(victim.id))
    }

    @Test
    fun googleVouchesOnlyForGmailAndItsWorkspaceDomain() = runTest {
        val ada = resolve(github("1", "ada@example.com"))

        // A consumer Google account with an outside address, or another Workspace: not an authority for it.
        assertFailsWith<AccountLinkRequiredException> { resolve(google("g-1", "ada@example.com")) }
        val otherWorkspace = google("g-2", "ada@example.com", hostedDomain = "evil.example")
        assertFailsWith<AccountLinkRequiredException> { resolve(otherWorkspace) }
        // The Workspace of example.com is: its administrator owns the address.
        assertEquals(ada.id, resolve(google("g-3", "ada@example.com", hostedDomain = "Example.com")).id)
        val gmail = resolve(github("2", "bob@googlemail.com"))
        assertEquals(gmail.id, resolve(google("g-4", "bob@googlemail.com")).id)
    }

    @Test
    fun vouchingNeedsAVerifiedAddress() {
        assertFalse(vouchesFor(github("1", "ada@example.com", verified = false), "ada@example.com"))
        assertTrue(vouchesFor(github("1", "ada@example.com"), "ada@example.com"))
        assertFalse(vouchesFor(sso("s", "ada@example.com"), "ada@example.com"))
    }

    @Test
    fun linkingAddsAWayInToTheSignedInUserOnly() = runTest {
        val link = LinkIdentityUseCaseImpl(users, audit, clock)
        val ada = resolve(github("1", "ada@gmail.com"))
        val bob = resolve(github("2", "bob@gmail.com"))

        // Ada, signed in, links her organization's SSO: one more way in, no address taken from it.
        assertEquals(ada, link(ada.id, sso("ada-sso", "someone@else.example")))
        assertEquals(2, users.listIssuers(ada.id).size)
        assertEquals(listOf(UserEmail("ada@gmail.com", true)), users.listEmails(ada.id))
        assertEquals("identity.linked", audit.entries.last().action)
        // Linking it again is harmless; linking Bob's GitHub to Ada is refused; an unknown user is refused.
        assertEquals(ada, link(ada.id, github("1", null)))
        assertFailsWith<IdentityTakenException> { link(ada.id, github("2", null)) }
        assertEquals(listOf("github"), users.listIssuers(bob.id))
        assertFailsWith<UserNotFoundException> { link(Uuid.random(), github("3", null)) }
    }

    @Test
    fun anOidcIdentityIsScopedToItsConnection() = runTest {
        val connection = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val identity = VerifiedIdentity(IdentityProvider.OIDC, "sub", null, false, connectionId = connection)

        assertEquals("oidc:00000000-0000-4000-8000-000000000001", identity.issuer)
        assertEquals("github", github("1", null).issuer)
    }

    @Test
    fun theProfileListsEmailsAndProviders() = runTest {
        val user = resolve(github("1", "ada@example.com"))
        val profile = GetProfileUseCaseImpl(users)(user.id)

        assertEquals(user, profile.user)
        assertEquals(listOf("github"), profile.issuers)
        assertFailsWith<UserNotFoundException> { GetProfileUseCaseImpl(users)(Uuid.random()) }
    }

    @Test
    fun aWebSessionLastsThirtyDaysWithAPrefixedSecretAndItsCsrfToken() = runTest {
        val sessions = InMemoryWebSessionsRepository()
        val started = StartWebSessionUseCaseImpl(sessions, CountingSecretGenerator(), FakeHasher(), clock)(Uuid.random())

        assertEquals("oss_secret1", started.secret)
        assertEquals("csrf(oss_secret1)", started.csrfToken)
        assertEquals(started.session, sessions.findBySecretHash("h(oss_secret1)"))
        assertEquals(clock.now + SESSION_LIFETIME, started.session.expiresAt)
    }

    @Test
    fun signingOutRevokesOneOrEverySession() = runTest {
        val sessions = InMemoryWebSessionsRepository()
        val start = StartWebSessionUseCaseImpl(sessions, CountingSecretGenerator(), FakeHasher(), clock)
        val end = EndWebSessionUseCaseImpl(sessions, audit, clock)
        val userId = Uuid.random()
        val first = start(userId)
        start(userId)
        start(Uuid.random())

        end(first.session.id)
        assertEquals(clock.now, sessions.findBySecretHash("h(oss_secret1)")?.revokedAt)
        assertEquals(1, end.everywhere(userId))
        assertEquals("sessions.revoked", audit.entries.last().action)
        assertEquals(mapOf("count" to "1"), audit.entries.last().details)
    }

    @Test
    fun nowMillisDropsSubMillisecondPrecision() {
        val clock = SettableClock(Instant.parse("2026-10-30T10:00:00.123456789Z"))

        assertEquals(Instant.parse("2026-10-30T10:00:00.123Z"), clock.nowMillis())
    }

    @Test
    fun providerKeysRoundTrip() {
        IdentityProvider.entries.forEach { assertEquals(it, IdentityProvider.of(it.key)) }
        assertFailsWith<IllegalArgumentException> { IdentityProvider.of("myspace") }
    }

    @Test
    fun theLoginComesFromGitHubThenTheEmailThenTheName() = runTest {
        assertEquals("ada-l", resolve(github("1", "x@example.com").copy(login = "Ada-L")).login)
        assertEquals("grace-hopper", resolve(google("2", "Grace.Hopper@example.com")).login)
        assertEquals("linus", resolve(VerifiedIdentity(IdentityProvider.OIDC, "3", null, false, "Linus")).login)
        assertEquals("user", resolve(VerifiedIdentity(IdentityProvider.OIDC, "4", null, false, "李")).login)
    }

    @Test
    fun aTakenLoginGetsANumberThenARandomSuffix() = runTest {
        assertEquals("ada", resolve(github("1", null, name = null).copy(login = "ada")).login)
        assertEquals("ada-2", resolve(github("2", null, name = null).copy(login = "ada")).login)
        (3..20).forEach { resolve(github("n$it", null, name = null).copy(login = "ada")) }
        val random = resolve(github("21", null, name = null).copy(login = "ada")).login

        assertTrue(Regex("ada-[0-9a-f]{8}").matches(random), random)
        val long = "a".repeat(39)
        assertEquals(long, resolve(github("22", null).copy(login = long)).login)
        assertEquals("a".repeat(37) + "-2", resolve(github("23", null).copy(login = long)).login)
    }

    @Test
    fun loginsAreCleanedUp() {
        assertEquals("ada-lovelace", loginOf("  Ada  Lovelace!! "))
        assertEquals("a-b", loginOf("--a__b--"))
        assertEquals("a".repeat(39), loginOf("a".repeat(50)))
        assertEquals("a".repeat(38), loginOf("a".repeat(38) + "-b"))
        assertNull(loginOf("___"))
        assertNull(loginOf(""))
    }
}
