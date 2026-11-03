// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.infrastructure.DAILY_TOTALS_OF_THE_LEDGER
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Real PostgreSQL: the daily totals (`usage_daily`, version 7) are the ledger's, added up by organization, UTC day,
 * member, session and launch, after every write of the ledger: a batch, a call settled, a delete under the
 * organization's policy, the retention purge of the router; and the totals version 7 writes from an existing ledger
 * are the same. A total is never zero, and an organization reads only its own.
 */
class UsageDailyTotalsPostgresTest {
    private val clock = TestClock()
    private val modules = listOf(SecretsModule, UsageModule)
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    // One line per total, nulls written `-`. No `::` cast and no colon at all: the driver reads `:x` as a parameter.
    private fun lines(source: String) =
        "SELECT CAST(org_id AS TEXT) || '|' || CAST(day AS TEXT) || '|' || COALESCE(CAST(member_id AS TEXT), '-') " +
            "|| '|' || COALESCE(session_id, '-') || '|' || COALESCE(launch_id, '-') || '|' || CAST(cost AS TEXT) " +
            "AS line FROM ($source) t ORDER BY 1"

    private suspend fun TestServer.read(sql: String): List<String> =
        database.superuser.fetchAll(Statement.create(sql)).getOrThrow().map { it.get("line").asString() }

    private suspend fun TestServer.assertTotalsAreTheLedgers(step: String) {
        val ledger = read(
            lines(
                "SELECT org_id, CAST(at AT TIME ZONE 'UTC' AS DATE) AS day, member_id, session_id, launch_id, " +
                    "SUM(cost_micros) AS cost FROM usage_events WHERE cost_micros > 0 GROUP BY 1, 2, 3, 4, 5",
            ),
        )
        val totals = read(
            lines("SELECT org_id, day, member_id, session_id, launch_id, cost_micros AS cost FROM usage_daily"),
        )
        assertTrue(ledger.isNotEmpty(), step)
        assertEquals(ledger, totals, step)
    }

    // Calls of org A over three days around midnight UTC, by two members and nobody, in a session, a launch, or
    // neither; one in five costs nothing. Each batch repeats its keys, and the second batch meets the first's.
    private fun batch(offset: Int) = List(40) { i ->
        val n = i + offset
        modelEvent(
            costMicros = if (n % 5 == 0) 0L else n * 10L,
            memberId = listOf(TestCallers.adminA.memberId, TestCallers.memberA.memberId, null)[n % 3],
            at = TestCallers.epoch - (n * 90).minutes,
        ).copy(
            sessionId = if (n % 4 == 1) null else "s-${n % 2}",
            launchId = if (n % 4 == 3) "l-${n % 2}" else null,
        )
    }

    @Test
    fun theDailyTotalsFollowEveryWriteOfTheLedger() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        repository.insert(TestCallers.orgA, batch(0))
        repository.insert(TestCallers.orgA, batch(20))
        server.assertTotalsAreTheLedgers("two batches, the second adding to the first's totals")

        repository.settle(modelEvent(700, at = TestCallers.epoch - 2.hours).copy(sessionId = "s-0"))
        repository.insert(TestCallers.orgB, listOf(modelEvent(5, memberId = null, orgId = TestCallers.orgB)))
        server.assertTotalsAreTheLedgers("a settled call, another organization")

        val delete = Statement.create("DELETE FROM usage_events WHERE session_id = 's-1'")
        val deleted = server.driver.executeInOrg(TestCallers.orgA, delete).getOrThrow()
        assertTrue(deleted > 0)
        server.assertTotalsAreTheLedgers("a delete under the organization's policy")

        assertTrue(repository.purge(TestCallers.epoch - 1.days, 1_000) > 0)
        server.assertTotalsAreTheLedgers("the retention purge, run by the router")
        val zero = server.database.superuser.fetchAll(
            Statement.create("SELECT COUNT(*) AS n FROM usage_daily WHERE cost_micros <= 0"),
        ).getOrThrow().first().get("n").asLong()
        assertEquals(0L, zero, "A total that reaches zero is dropped")

        server.database.superuser.execute("DELETE FROM usage_daily").getOrThrow()
        server.database.superuser.execute(DAILY_TOTALS_OF_THE_LEDGER).getOrThrow()
        server.assertTotalsAreTheLedgers("the totals version 7 writes from the ledger")

        val seenByB = server.driver.fetchInOrg(
            TestCallers.orgB,
            Statement.create("SELECT COUNT(*) AS n FROM usage_daily"),
        ).getOrThrow().first().get("n").asLong()
        assertEquals(1L, seenByB, "An organization reads only its own totals")
    }
}
