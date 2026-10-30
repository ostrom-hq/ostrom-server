package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLogImpl
import dev.ostrom.server.identity.domain.InvalidEmailException
import dev.ostrom.server.identity.domain.ListAuditEventsUseCaseImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.USER_CODE_ALPHABET
import dev.ostrom.server.identity.domain.auditCanonicalForm
import dev.ostrom.server.identity.domain.constantTimeEquals
import dev.ostrom.server.identity.domain.normalizeEmail
import dev.ostrom.server.identity.domain.validateScopes
import dev.ostrom.server.identity.fakes.InMemoryAuditRepository
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.infrastructure.InMemoryRateLimiter
import dev.ostrom.server.identity.infrastructure.SecureSecretGenerator
import dev.ostrom.server.identity.infrastructure.Sha256SecretHasher
import dev.ostrom.server.identity.presentation.IdentityConfig
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SupportTest {
    @Test
    fun auditEventsAreChainedHashedAndSignedByTheInstanceKey() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val repository = InMemoryAuditRepository()
        val hasher = Sha256SecretHasher()
        val log = AuditLogImpl(repository, signer, hasher, SettableClock(Instant.parse("2026-10-30T10:00:00.123456Z")))
        val orgId = Uuid.random()

        val first = log.record(AuditEntry(orgId, Uuid.random(), "member.added", "member", "m1", mapOf("z" to "1", "a" to "2")))
        val second = log.record(AuditEntry(orgId, null, "member.removed", "member", "m1"))
        val other = log.record(AuditEntry(null, null, "user.created", "user", "u1"))

        assertEquals(listOf(1L, 2L, 1L), listOf(first.sequence, second.sequence, other.sequence))
        assertNull(first.previousHash)
        assertEquals(first.hash, second.previousHash)
        assertEquals(Instant.parse("2026-10-30T10:00:00.123Z"), first.occurredAt)
        assertEquals(signer.keyId, first.keyId)
        for (event in listOf(first, second, other)) {
            val canonical = auditCanonicalForm(event.id, event.sequence, event.entry, event.occurredAt, event.previousHash)
            assertEquals(hasher.hash(canonical), event.hash)
            assertTrue(signer.verify(canonical.encodeToByteArray(), Base64.decode(event.signature)))
        }
        val listed = ListAuditEventsUseCaseImpl(repository)(orgId, null, null, 500)
        assertEquals(listOf(second, first), listed)
        assertEquals(listOf(first), ListAuditEventsUseCaseImpl(repository)(orgId, null, 2, 0))
    }

    @Test
    fun theCanonicalFormHasSortedKeysAndExplicitNulls() {
        val id = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val canonical = auditCanonicalForm(
            id, 3, AuditEntry(null, null, "a.b", "t", "x", mapOf("b" to "\"q\"", "a" to "é")),
            Instant.parse("2026-10-30T10:00:00Z"), "abc",
        )

        assertEquals(
            """{"action":"a.b","actorId":null,"details":{"a":"é","b":"\"q\""},"id":"00000000-0000-4000-8000-000000000001",""" +
                """"occurredAt":"2026-10-30T10:00:00Z","orgId":null,"previousHash":"abc","sequence":3,"targetId":"x",""" +
                """"targetType":"t","v":1}""",
            canonical,
        )
    }

    @Test
    fun secretsAreLongUrlSafeAndDistinct() {
        val generator = SecureSecretGenerator()
        val secrets = (1..50).map { generator.secret() }

        assertEquals(50, secrets.toSet().size)
        assertTrue(secrets.all { it.length == 43 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } })
    }

    @Test
    fun userCodesUseTheAlphabetWithADash() {
        val generator = SecureSecretGenerator()
        // Enough codes that every branch of the rejection sampling runs (a byte >= 247 is 9 in 256).
        val codes = (1..300).map { generator.userCode() }

        assertTrue(codes.all { it.length == 9 && it[4] == '-' })
        assertTrue(codes.all { code -> code.filter { it != '-' }.all { it in USER_CODE_ALPHABET } })
        assertTrue(codes.toSet().size > 290)
    }

    @Test
    fun theHasherIsSha256AndTheCsrfTokenIsDomainSeparated() = runTest {
        val hasher = Sha256SecretHasher()

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hasher.hash("abc"))
        val csrf = hasher.csrfToken("oss_abc")
        assertEquals(43, csrf.length)
        assertEquals(csrf, hasher.csrfToken("oss_abc"))
        assertFalse(csrf == hasher.csrfToken("oss_abd"))
    }

    @Test
    fun theRateLimiterCountsPerKeyAndWindow() = runTest {
        val clock = SettableClock()
        val limiter = InMemoryRateLimiter(clock)

        assertTrue(limiter.tryAcquire("a", 2, 1.minutes))
        assertTrue(limiter.tryAcquire("a", 2, 1.minutes))
        assertFalse(limiter.tryAcquire("a", 2, 1.minutes))
        assertTrue(limiter.tryAcquire("b", 2, 1.minutes))
        clock.now += 61.seconds
        assertTrue(limiter.tryAcquire("a", 2, 1.minutes))
    }

    @Test
    fun theRateLimiterBoundsItsMemory() = runTest {
        val clock = SettableClock()
        val limiter = InMemoryRateLimiter(clock, maxKeys = 2)

        limiter.tryAcquire("a", 1, 1.minutes)
        clock.now += 2.minutes
        limiter.tryAcquire("b", 1, 1.minutes)
        // "a" expired: it is dropped first.
        limiter.tryAcquire("c", 1, 1.minutes)
        // Nothing expired: the oldest ("b") is dropped, so it starts a new window.
        limiter.tryAcquire("d", 1, 1.minutes)
        assertTrue(limiter.tryAcquire("b", 1, 1.minutes))
    }

    @Test
    fun emailsAreNormalizedOrRefused() {
        assertEquals("ada@example.com", normalizeEmail("  Ada@Example.com "))
        listOf("", "ada", "@example.com", "ada@", "ada@example", "a@b@c.com", "ada @example.com", "ada@.com", "ada@com.",
            "a".repeat(250) + "@x.com").forEach { assertFailsWith<InvalidEmailException>(it) { normalizeEmail(it) } }
    }

    @Test
    fun scopesAreDeduplicated() {
        assertEquals(setOf("orgs:read"), validateScopes(listOf("orgs:read", "orgs:read")))
    }

    @Test
    fun comparisonIsExact() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "ab"))
    }

    @Test
    fun tokenKindsRoundTrip() {
        TokenKind.entries.forEach { assertEquals(it, TokenKind.of(it.name)) }
    }

    @Test
    fun theConfigurationHasLocalDefaultsAndTrimsSlashes() {
        val defaults = IdentityConfig.from(MapConfigSource(emptyMap()))
        assertEquals("http://localhost:8080", defaults.publicUrl)
        assertEquals("http://localhost:5173", defaults.consoleUrl)
        assertFalse(defaults.secureCookies)

        val production = IdentityConfig.from(
            MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://api.ostrom.run/", "OSTROM_CONSOLE_URL" to " https://app.ostrom.run ")),
        )
        assertEquals("https://api.ostrom.run", production.publicUrl)
        assertEquals("https://app.ostrom.run", production.consoleUrl)
        assertTrue(production.secureCookies)
        assertEquals("http://localhost:8080", IdentityConfig.from(MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to " "))).publicUrl)
        assertFailsWith<InvalidConfigException> { IdentityConfig.from(MapConfigSource(mapOf("OSTROM_CONSOLE_URL" to "ftp://x"))) }
    }
}
