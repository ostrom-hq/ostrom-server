// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.kernel.database.DatabaseNotifications
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.infrastructure.ReplicatedUsageFeed
import run.ostrom.server.usage.infrastructure.UsageFeedImpl
import run.ostrom.server.usage.infrastructure.UsageFeedReadsImpl
import run.ostrom.server.usage.infrastructure.UsageRepositoryImpl
import run.ostrom.server.usage.infrastructure.usageFeedReadSql
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The live feed across replicas on real PostgreSQL (TC-REP-1): two feeds over one database, as two replicas of the
 * server, the second's subscriber seeing what the first recorded through `LISTEN`/`NOTIFY`, read inside its org.
 */
class ReplicatedUsageFeedPostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    private fun TestServer.replica() = ReplicatedUsageFeed(
        UsageFeedImpl(),
        koin.get<DatabaseNotifications>(),
        UsageFeedReadsImpl(driver),
        RecordingLogger(),
    )

    @Test
    fun aSubscriberOnOneReplicaSeesWhatTheOtherRecorded() =
        serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
            val (a, b) = server.replica() to server.replica()
            val event = modelEvent(321)
            UsageRepositoryImpl(server.driver).insert(TestCallers.orgA, listOf(event))
            coroutineScope {
                val running = listOf(launch { a.run() }, launch { b.run() })
                val onB = mutableListOf<UsageEvent>()
                val ofOrgB = mutableListOf<UsageEvent>()
                val following = listOf(
                    launch { b.subscribe(TestCallers.orgA).toList(onB) },
                    launch { b.subscribe(TestCallers.orgB).toList(ofOrgB) },
                )
                // LISTEN starts on a connection of its own: told again until the other replica hears it.
                withTimeout(20.seconds) {
                    while (onB.isEmpty()) {
                        a.publish(event)
                        delay(200.milliseconds)
                    }
                }
                assertEquals(event.id, onB.first().id)
                assertEquals(321L, onB.first().costMicros, "Read from the ledger")
                assertEquals(emptyList(), ofOrgB, "Another org's subscriber sees nothing")
                (running + following).forEach { it.cancel() }
            }
        }

    @Test
    fun theReadOfToldEventsScansNoTable() = serverTest(listOf(SecretsModule, UsageModule), overrides) { server ->
        // Fixtures only: the rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        listOf(TestCallers.orgB to 20_000, TestCallers.orgA to 2_000).forEach { (org, count) ->
            fixtures.execute(
                "INSERT INTO usage_events (id, org_id, member_id, kind, name, input_tokens, output_tokens, " +
                    "cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) " +
                    "SELECT gen_random_uuid(), CAST('$org' AS uuid), NULL, 'MODEL', 'claude', 1, 1, 0, 0, i, 1, " +
                    "'ok', now() - i * INTERVAL '1 second' FROM generate_series(1, $count) AS i",
            ).getOrThrow()
        }
        fixtures.execute("VACUUM ANALYZE usage_events").getOrThrow()

        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${usageFeedReadSql(3)}")
            .bind("org_id", TestCallers.orgA)
        repeat(3) { statement.bind("id$it", Uuid.random()) }
        val plan = server.driver.inTenant(TestCallers.orgA) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        assertFalse("Seq Scan" in plan, "The read scans the ledger:\n$plan")
    }
}
