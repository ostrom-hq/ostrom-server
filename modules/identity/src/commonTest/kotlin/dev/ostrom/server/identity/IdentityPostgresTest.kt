package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.DeviceAuthorization
import dev.ostrom.server.identity.domain.DeviceAuthorizationStatus
import dev.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import dev.ostrom.server.identity.domain.DuplicateIdentityException
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.WebSession
import dev.ostrom.server.identity.domain.WebSessionsRepository
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The repositories against a real PostgreSQL (CI service), with the migrations of the module. */
class IdentityPostgresTest {
    private val now = Instant.parse("2026-10-30T10:00:00.123Z")

    private fun user(name: String = "Ada") = User(Uuid.random(), name, null, null, now)

    private fun github(subject: String, email: String? = null) =
        VerifiedIdentity(IdentityProvider.GITHUB, subject, email, email != null)

    private fun token(userId: Uuid, kind: TokenKind = TokenKind.PERSONAL, expiresAt: Instant? = null, session: String? = null) =
        ApiToken(
            Uuid.random(), userId, if (kind == TokenKind.AGENT) Uuid.random() else null, "t", kind, setOf("b:read", "a:read"),
            "osp_abc", session, now, expiresAt, null, null,
        )

    @Test
    fun usersAreFoundByIdentityAndVerifiedEmailOnly() = serverTest(listOf(IdentityModule)) { server ->
        val users = server.koin.get<UsersRepository>()
        val ada = user()
        users.create(ada, github("1", "ada@example.com"), UserEmail("ada@example.com", true), now)
        val bob = user("Bob")
        users.create(bob, github("2"), UserEmail("bob@example.com", false), now)

        assertEquals(ada, users.get(ada.id))
        assertNull(users.get(Uuid.random()))
        assertEquals(ada, users.findByIdentity("github", "1"))
        assertNull(users.findByIdentity("google", "1"))
        assertEquals(ada, users.findByVerifiedEmail("ada@example.com"))
        assertNull(users.findByVerifiedEmail("bob@example.com"))

        users.link(bob.id, VerifiedIdentity(IdentityProvider.GOOGLE, "g", "bob@example.com", true), UserEmail("bob@example.com", true), now)
        users.link(bob.id, github("2"), UserEmail("bob@example.com", false), now)
        assertEquals(listOf(UserEmail("bob@example.com", true)), users.listEmails(bob.id), "Verified stays verified")
        assertEquals(listOf("github", "google"), users.listIssuers(bob.id))
        // Linking an identity of another user changes nothing and fails, so the caller retries.
        assertFailsWith<DuplicateIdentityException> { users.link(bob.id, github("1"), null, now) }
        assertEquals(ada, users.findByIdentity("github", "1"))
    }

    @Test
    fun twoUsersCannotVerifyTheSameEmail() = serverTest(listOf(IdentityModule)) { server ->
        val users = server.koin.get<UsersRepository>()
        users.create(user(), github("1"), UserEmail("ada@example.com", true), now)

        assertFailsWith<DuplicateIdentityException> {
            users.create(user(), github("2"), UserEmail("ada@example.com", true), now)
        }
        assertFailsWith<DuplicateIdentityException> { users.create(user(), github("1"), null, now) }
        assertNull(users.findByIdentity("github", "2"), "The failed creation was rolled back")
    }

    @Test
    fun sessionsAreFoundByHashTouchedAndRevoked() = serverTest(listOf(IdentityModule)) { server ->
        val users = server.koin.get<UsersRepository>()
        val sessions = server.koin.get<WebSessionsRepository>()
        val ada = user().also { users.create(it, github("1"), null, now) }
        val first = WebSession(Uuid.random(), ada.id, now, now + 30.days, now, null)
        val second = first.copy(id = Uuid.random())
        sessions.create(first, "hash-1")
        sessions.create(second, "hash-2")

        assertEquals(first, sessions.findBySecretHash("hash-1"))
        assertNull(sessions.findBySecretHash("hash-3"))
        sessions.touch(first.id, now + 1.days)
        assertEquals(now + 1.days, sessions.findBySecretHash("hash-1")?.lastSeenAt)
        sessions.revoke(first.id, now + 2.days)
        sessions.revoke(first.id, now + 3.days)
        assertEquals(now + 2.days, sessions.findBySecretHash("hash-1")?.revokedAt, "Revoked once")
        assertEquals(1, sessions.revokeAllOfUser(ada.id, now + 4.days))
    }

    @Test
    fun tokensAreListedRotatedAndRevoked() = serverTest(listOf(IdentityModule)) { server ->
        val users = server.koin.get<UsersRepository>()
        val tokens = server.koin.get<ApiTokensRepository>()
        val ada = user().also { users.create(it, github("1"), null, now) }
        val personal = token(ada.id)
        val cli = token(ada.id, TokenKind.CLI, expiresAt = now + 1.days).copy(createdAt = now + 1.days)
        val expired = token(ada.id, expiresAt = now)
        val agent = token(ada.id, TokenKind.AGENT, session = "run-1")
        listOf(personal, cli, expired, agent).forEachIndexed { index, it -> tokens.create(it, "hash-$index") }
        val kinds = setOf(TokenKind.PERSONAL, TokenKind.CLI)

        assertEquals(personal, tokens.findBySecretHash("hash-0"))
        assertEquals(agent, tokens.findBySecretHash("hash-3"))
        assertEquals(personal, tokens.get(ada.id, personal.id))
        assertNull(tokens.get(Uuid.random(), personal.id))
        assertEquals(listOf(cli, personal), tokens.listLive(ada.id, kinds, now))
        assertEquals(2, tokens.countLive(ada.id, kinds, now))
        tokens.touch(personal.id, now + 1.days)
        assertEquals(now + 1.days, tokens.get(ada.id, personal.id)?.lastUsedAt)

        assertTrue(!tokens.revoke(ada.id, agent.id, now), "An agent token is not revoked as a user token")
        val replacement = token(ada.id)
        assertTrue(tokens.rotate(ada.id, personal.id, replacement, "hash-4", now))
        assertTrue(!tokens.rotate(ada.id, personal.id, token(ada.id), "hash-5", now), "Rotated once")
        assertTrue(!tokens.rotate(ada.id, expired.id, token(ada.id), "hash-6", now), "An expired token does not rotate")
        assertNull(tokens.findBySecretHash("hash-5"))
        assertEquals(listOf(cli, replacement), tokens.listLive(ada.id, kinds, now))
        assertTrue(tokens.revoke(ada.id, cli.id, now))
        assertTrue(!tokens.revoke(ada.id, cli.id, now))
        assertEquals(1, tokens.revokeOfAgentSession("run-1", now))
    }

    @Test
    fun aDeviceAuthorizationGivesOneTokenEvenToConcurrentPolls() = serverTest(listOf(IdentityModule)) { server ->
        val users = server.koin.get<UsersRepository>()
        val devices = server.koin.get<DeviceAuthorizationsRepository>()
        val tokens = server.koin.get<ApiTokensRepository>()
        val ada = user().also { users.create(it, github("1"), null, now) }
        val pending = DeviceAuthorization(Uuid.random(), "BCDF-GHJK", "laptop", DeviceAuthorizationStatus.PENDING, null, now, now + 1.days, null)
        devices.create(pending, "device-hash")

        assertEquals(pending, devices.findByDeviceCodeHash("device-hash"))
        assertEquals(pending, devices.findPendingByUserCode("BCDF-GHJK", now))
        assertNull(devices.findPendingByUserCode("BCDF-GHJK", now + 2.days))
        assertTrue(!devices.answer(pending.id, ada.id, true, now + 2.days), "Expired")
        assertTrue(devices.answer(pending.id, ada.id, true, now))
        assertTrue(!devices.answer(pending.id, ada.id, false, now), "Answered once")
        devices.markPolled(pending.id, now)
        assertEquals(now, devices.findByDeviceCodeHash("device-hash")?.lastPolledAt)

        val results = coroutineScope {
            (1..5).map { index -> async { devices.consume(pending.id, token(ada.id, TokenKind.CLI), "cli-$index") } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertEquals(1, tokens.listLive(ada.id, setOf(TokenKind.CLI), now).size)
        assertEquals(DeviceAuthorizationStatus.CONSUMED, devices.findByDeviceCodeHash("device-hash")?.status)
    }

    @Test
    fun auditChainsStayOrderedUnderConcurrentAppends() = serverTest(listOf(IdentityModule)) { server ->
        val audit = server.koin.get<AuditLog>()
        val repository = server.koin.get<AuditRepository>()
        val orgId = Uuid.random()
        val actor = Uuid.random()

        coroutineScope {
            (1..10).map { index -> async { audit.record(AuditEntry(orgId, actor, "test.$index", "t", "$index")) } }.awaitAll()
        }
        audit.record(AuditEntry(null, actor, "user.created", "user", actor.toString(), mapOf("provider" to "github")))

        val chain = repository.list(orgId, null, null, 200)
        assertEquals((10L downTo 1L).toList(), chain.map { it.sequence })
        chain.zipWithNext().forEach { (newer, older) -> assertEquals(older.hash, newer.previousHash) }
        assertEquals(listOf(chain[6], chain[7]), repository.list(orgId, null, 5, 2))
        val account = repository.list(null, actor, null, 10).single()
        assertEquals(mapOf("provider" to "github"), account.entry.details)
        assertEquals(emptyList(), repository.list(null, Uuid.random(), null, 10))
        assertFailsWith<IllegalArgumentException> { repository.list(null, null, null, 10) }
    }

    @Test
    fun rowLevelSecurityConfinesAQueryToItsOrganization() = serverTest(listOf(IdentityModule)) { server ->
        val audit = server.koin.get<AuditLog>()
        val orgA = Uuid.random()
        val orgB = Uuid.random()
        audit.record(AuditEntry(orgA, null, "a", "t", "1"))
        audit.record(AuditEntry(orgB, null, "b", "t", "2"))
        audit.record(AuditEntry(null, null, "account", "t", "3"))

        // No WHERE clause at all: row-level security alone keeps the other organization's rows out.
        val seen = server.driver.inOrg(orgA) {
            fetchAll("SELECT action FROM identity_audit_events").getOrThrow().map { it.get("action").asString() }
        }
        assertEquals(listOf("a"), seen)
        assertFailsWith<Exception>("Writing a row of another organization is refused") {
            server.driver.inOrg(orgA) {
                execute(
                    Statement.create(
                        "INSERT INTO identity_audit_events (id, chain, sequence, org_id, action, target_type, target_id, " +
                            "details, occurred_at, hash, signature, key_id) VALUES (:id, :chain, 99, :org, 'x', 't', '1', '{}', " +
                            "now(), 'h', 's', 'k')",
                    ).bind("id", Uuid.random()).bind("chain", orgB.toString()).bind("org", orgB),
                ).getOrThrow()
            }
        }
    }
}
