// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import org.jetbrains.exposed.v1.core.plus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.usage.infrastructure.UsageEvents
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.rem
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.infrastructure.DAILY_TOTALS_OF_THE_LEDGER
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
 * organization's policy, the retention purge of the router, an update and a truncate by another role; and the totals
 * version 7 writes from an existing ledger are the same. A total is never zero, and an organization reads only its
 * own and writes none.
 */
class UsageDailyTotalsPostgresTest {
    private val clock = TestClock()
    private val modules = listOf(SecretsModule, UsageModule)
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    // One line per total, nulls written `-`.
    private fun lines(source: String) =
        "SELECT CAST(org_id AS TEXT) || '|' || CAST(day AS TEXT) || '|' || COALESCE(CAST(member_id AS TEXT), '-') " +
            "|| '|' || COALESCE(session_id, '-') || '|' || COALESCE(launch_id, '-') || '|' || CAST(cost AS TEXT) " +
            "AS line FROM ($source) t ORDER BY 1"

    private suspend fun TestServer.read(sql: String): List<String> =
        superuserJdbc().query {
            exec(sql) { rs -> buildList { while (rs.next()) add(rs.getString("line")) } }.orEmpty()
        }

    private fun TestServer.superuserJdbc() = database.superuserJdbc

    private suspend fun TestServer.count(sql: String): Long = superuserJdbc().query {
        exec(sql) { rs -> rs.next(); rs.getLong("n") }
    }!!

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

        val deleted = server.koin.get<JdbcDatabase>().inOrg(TestCallers.orgA) {
            UsageEvents.deleteWhere { sessionId eq "s-1" }
        }
        assertTrue(deleted > 0)
        server.assertTotalsAreTheLedgers("a delete under the organization's policy")

        assertTrue(repository.purge(TestCallers.epoch - 1.days, 1_000) > 0)
        server.assertTotalsAreTheLedgers("the retention purge, run by the router")
        val zero = server.count("SELECT COUNT(*) AS n FROM usage_daily WHERE cost_micros <= 0")
        assertEquals(0L, zero, "A total that reaches zero is dropped")

        val owner = server.database.superuserJdbc
        owner.query {
            UsageEvents.update({ UsageEvents.costMicros % 2L eq 1L }) {
                it[costMicros] = costMicros + 3L
                it[sessionId] = "moved"
            }
        }
        server.assertTotalsAreTheLedgers("an update by another role than the organization's")

        owner.query {
            UsageEvents.update({ (UsageEvents.sessionId eq "moved") and (UsageEvents.orgId eq TestCallers.orgA) }) {
                it[costMicros] = 0L
            }
        }
        server.assertTotalsAreTheLedgers("an update that brings calls to zero")

        // The backfill as version 7 runs it: by the router, under the ledger's forced policies.
        owner.query { exec("DELETE FROM usage_daily") }
        server.database.ownerJdbc.query {
            exec("SET LOCAL ROLE ostrom_router")
            exec(DAILY_TOTALS_OF_THE_LEDGER)
        }
        server.assertTotalsAreTheLedgers("the totals version 7 writes from the ledger")

        assertFailsWith<ExposedSQLException>("An organization never writes its totals") {
            server.koin.get<JdbcDatabase>().inOrg(TestCallers.orgA) { exec("UPDATE usage_daily SET cost_micros = 1") }
        }
        val seenByB = server.koin.get<JdbcDatabase>().inOrg(TestCallers.orgB) {
            exec("SELECT COUNT(*) AS n FROM usage_daily") { rs -> rs.next(); rs.getLong("n") }
        }
        assertEquals(1L, seenByB, "An organization reads only its own totals")

        owner.query { exec("TRUNCATE usage_events") }
        val left = server.count("SELECT COUNT(*) AS n FROM usage_daily")
        assertEquals(0L, left, "A truncated ledger has no totals")
    }
}
