package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.DuplicateIdentityException
import dev.ostrom.server.identity.domain.EndWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.GetProfileUseCaseImpl
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import dev.ostrom.server.identity.domain.SESSION_LIFETIME
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UserNotFoundException
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.nowMillis
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AccountUseCasesTest {
    private val users = InMemoryUsersRepository()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val resolve = ResolveUserUseCaseImpl(users, audit, clock)

    private fun github(subject: String, email: String?, verified: Boolean = true, name: String? = "Ada") =
        VerifiedIdentity(IdentityProvider.GITHUB, subject, email, verified, name, "https://avatars.example/a.png")

    private fun google(subject: String, email: String?, verified: Boolean = true) =
        VerifiedIdentity(IdentityProvider.GOOGLE, subject, email, verified)

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
        val first = resolve(github("1", "ada@example.com"))
        val second = resolve(google("g-1", "ADA@example.com"))

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
        val ada = resolve(github("1", "ada@example.com"))
        val bob = resolve(google("g-2", "bob@example.com"))
        // Bob's Google account now reports Ada's verified address: Bob keeps his account, Ada keeps her address.
        val again = resolve(google("g-2", "ada@example.com"))

        assertEquals(bob.id, again.id)
        assertEquals(listOf(UserEmail("bob@example.com", true)), users.listEmails(bob.id))
        assertEquals(listOf(UserEmail("ada@example.com", true)), users.listEmails(ada.id))
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
}
