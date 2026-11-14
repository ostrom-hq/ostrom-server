// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.infrastructure.UsageRepositoryImpl
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL: a member's minute of declared events is a row counted by the database's clock, so two replicas (two
 * repositories, nothing shared in memory) admit against the same count.
 */
class UsageIngestMinutesPostgresTest {
    private val overrides: Module = module {
        includes(TestCallers.overrides(TestClock()))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val member = TestCallers.memberA.memberId

    /** Starts away from a minute's end, so that every batch of a test falls in the same minute of the database. */
    private suspend fun TestServer.awayFromTheMinutesEnd() {
        val second = driver.fetchAll(Statement.create("SELECT CAST(EXTRACT(SECOND FROM now()) AS BIGINT) AS s"))
            .getOrThrow().first().get("s").asLong()
        if (second >= LATE_SECOND) delay((SECONDS_A_MINUTE - second + 1).seconds)
    }

    private fun events(count: Int, memberId: Uuid = member, orgId: Uuid = TestCallers.orgA) =
        List(count) { modelEvent(0, memberId = memberId, orgId = orgId) }

    @Test
    fun twoReplicasCountTheSameMinute() = serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
        server.awayFromTheMinutesEnd()
        val one = UsageRepositoryImpl(server.driver)
        val other = UsageRepositoryImpl(server.driver)
        val count = Statement.create("SELECT COUNT(*) AS n FROM usage_events")
        suspend fun recorded(org: Uuid = TestCallers.orgA) =
            server.driver.fetchInOrg(org, count).getOrThrow().first().get("n").asLong()

        assertTrue(one.insertWithinMinute(TestCallers.orgA, member, events(5), perMinute = 8))
        assertFalse(other.insertWithinMinute(TestCallers.orgA, member, events(4), 8), "The other replica sees the 5")
        assertEquals(5L, recorded(), "A refused batch writes nothing")
        assertTrue(other.insertWithinMinute(TestCallers.orgA, member, events(3), 8), "Nor counts anything")
        assertFalse(one.insertWithinMinute(TestCallers.orgA, member, events(1), 8))

        val admin = TestCallers.adminA.memberId
        assertTrue(one.insertWithinMinute(TestCallers.orgA, admin, events(8, admin), 8), "Another member's minute")
        assertTrue(
            one.insertWithinMinute(TestCallers.orgB, member, events(8, orgId = TestCallers.orgB), 8),
            "Another org's minute",
        )
        assertFalse(one.insertWithinMinute(TestCallers.orgA, admin, events(9, admin), 9), "A batch over the limit")
        assertTrue(one.insertWithinMinute(TestCallers.orgA, admin, emptyList(), 8), "An empty batch writes nothing")
        assertEquals(16L, recorded())
        assertEquals(8L, recorded(TestCallers.orgB))
    }

    @Test
    fun concurrentBatchesNeverOvershoot() = serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
        server.awayFromTheMinutesEnd()
        val replicas = List(2) { UsageRepositoryImpl(server.driver) }
        val admitted = coroutineScope {
            List(10) { index ->
                async { replicas[index % 2].insertWithinMinute(TestCallers.orgA, member, events(1), perMinute = 6) }
            }.awaitAll()
        }
        assertEquals(6, admitted.count { it }, "Counted one after the other under the minute's row lock")
    }

    @Test
    fun aBatchDropsTheMembersPastMinutes() = serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
        val repository = UsageRepositoryImpl(server.driver)
        val past = Statement.create(
            "INSERT INTO usage_ingest_minutes (org_id, member_id, minute, events) VALUES (:org_id, :member_id, 5, 9)",
        ).bind("org_id", TestCallers.orgA).bind("member_id", member)
        server.driver.executeInOrg(TestCallers.orgA, past).getOrThrow()
        assertTrue(repository.insertWithinMinute(TestCallers.orgA, member, events(1), perMinute = 2))
        val rows = Statement.create("SELECT COUNT(*) AS n, MAX(events) AS e FROM usage_ingest_minutes")
        val row = server.driver.fetchInOrg(TestCallers.orgA, rows).getOrThrow().first()
        assertEquals(1L to 1L, row.get("n").asLong() to row.get("e").asLong(), "Only the minute of now is left")
    }
}

private const val LATE_SECOND = 50L
private const val SECONDS_A_MINUTE = 60L
