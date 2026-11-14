// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.infrastructure.IdentityRateLimits
import run.ostrom.server.identity.infrastructure.RateLimiterImpl
import run.ostrom.server.identity.infrastructure.Sha256SecretHasher
import run.ostrom.server.identity.infrastructure.attempt
import run.ostrom.server.identity.infrastructure.windowsOver
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The sign-in rate limits across replicas on real PostgreSQL (TC-REP-2): two limiters over one database, as two
 * replicas of the server, count one window, which the database's clock ends; the keys are stored hashed, and the
 * windows over are purged.
 */
class RateLimitsPostgresTest {
    private fun TestServer.replica() = RateLimiterImpl(koin.get<JdbcDatabase>(), Sha256SecretHasher())

    private suspend fun TestServer.count(sql: String): Long =
        database.superuser.fetchAll(sql).getOrThrow().single().get("n").asLong()

    @Test
    fun twoReplicasCountingAtOnceAdmitTheLimitOnceNotOnceEach() = serverTest(listOf(IdentityModule)) { server ->
        val replicas = listOf(server.replica(), server.replica())
        val admitted = coroutineScope {
            (0 until 20).map { i ->
                async { replicas[i % 2].tryAcquire("device-start:203.0.113.7", 5, 10.minutes) }
            }.awaitAll()
        }
        assertEquals(5, admitted.count { it }, "Five attempts across both replicas, not five each")
        assertTrue(replicas[0].tryAcquire("device-start:203.0.113.8", 5, 10.minutes), "Another key is not held back")
    }

    @Test
    fun theDatabasesClockEndsAWindowAndTheKeysAreStoredHashed() = serverTest(listOf(IdentityModule)) { server ->
        val (a, b) = server.replica() to server.replica()
        val key = "magic-link-to:someone@example.com"
        assertTrue(a.tryAcquire(key, 1, 15.minutes))
        assertFalse(b.tryAcquire(key, 1, 15.minutes), "The other replica sees the window")
        val stored = server.database.superuser.fetchAll("SELECT key_hash FROM identity_rate_limits")
            .getOrThrow().single().get("key_hash").asString()
        assertEquals(Sha256SecretHasher().hash(key), stored)
        assertFalse("example.com" in stored, "No e-mail address in the table")

        // Fifteen minutes later by the database's clock, whatever the replicas' clocks say.
        server.database.superuser.execute("UPDATE identity_rate_limits SET ends_at = now() - INTERVAL '1 second'")
            .getOrThrow()
        assertTrue(b.tryAcquire(key, 1, 15.minutes), "A new window opens once the last one is over")
        assertFalse(a.tryAcquire(key, 1, 15.minutes), "And counts from one again")
        val ends = server.count(
            "SELECT count(*) AS n FROM identity_rate_limits " +
                "WHERE ends_at BETWEEN now() + INTERVAL '14 minutes' AND now() + INTERVAL '15 minutes'",
        )
        assertEquals(1L, ends, "The new window ends fifteen minutes after its first attempt")
    }

    @Test
    fun theWindowsOverArePurgedAndTheLiveOnesKept() = serverTest(listOf(IdentityModule)) { server ->
        val limiter = server.replica()
        listOf("a", "b", "c").forEach { limiter.tryAcquire(it, 1, 10.minutes) }
        server.database.superuser.execute(
            "UPDATE identity_rate_limits SET ends_at = now() - INTERVAL '1 minute' " +
                "WHERE key_hash IN ('${Sha256SecretHasher().hash("a")}', '${Sha256SecretHasher().hash("b")}')",
        ).getOrThrow()

        assertEquals(2L, limiter.purgeExpired())
        assertEquals(1L, server.count("SELECT count(*) AS n FROM identity_rate_limits"))
        assertFalse(limiter.tryAcquire("c", 1, 10.minutes), "The live window still counts")
    }

    @Test
    fun anAttemptAndThePurgeScanNoTable() = serverTest(listOf(IdentityModule)) { server ->
        // Fixtures only: a filled table, mostly live windows, and its statistics.
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO identity_rate_limits (key_hash, attempts, ends_at) SELECT md5('k' || i), 1, " +
                "now() + (i % 600) * INTERVAL '1 second' + INTERVAL '1 minute' FROM generate_series(1, 50000) AS i",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_rate_limits").getOrThrow()

        val counted = fixtures.fetchAll("SELECT key_hash FROM identity_rate_limits LIMIT 1").getOrThrow()
            .single().get("key_hash").asString()
        val first = Sha256SecretHasher().hash("device-start:198.51.100.1")
        val attempts: Map<String, StatementBuilder.() -> Statement<*>> = mapOf(
            "a first attempt" to { attempt(first, 600L) },
            "a counted attempt" to { attempt(counted, 600L) },
            "the purge" to { IdentityRateLimits.deleteWhere(op = windowsOver) },
        )
        attempts.forEach { (what, statement) ->
            val plan = server.koin.get<JdbcDatabase>().query { plan(statement) }
            assertFalse("Seq Scan" in plan, "$what scans the table:\n$plan")
        }
    }
}
