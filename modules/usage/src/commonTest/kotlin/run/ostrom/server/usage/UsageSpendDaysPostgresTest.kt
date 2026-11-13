// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.infrastructure.SPEND_DAYS_OF_THE_LEDGER
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Real PostgreSQL: the spend of each day (`usage_spend_days`, version 10, TeamCode #163) is the ledger's, added up by
 * organization, UTC day, member and workflow, after every write of the ledger; the spend reads that use it give what
 * adding up the calls gives, from a midnight or from any instant; and an organization reads only its own and writes
 * none.
 */
class UsageSpendDaysPostgresTest {
    private val clock = TestClock()
    private val modules = listOf(SecretsModule, UsageModule)
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }

    // One line per day's spend, nulls written `-`. No `::` cast and no colon at all: the driver reads `:x` as one.
    private fun lines(source: String) =
        "SELECT CAST(org_id AS TEXT) || '|' || CAST(day AS TEXT) || '|' || COALESCE(CAST(member_id AS TEXT), '-') " +
            "|| '|' || COALESCE(workflow_id, '-') || '|' || CAST(cost AS TEXT) AS line FROM ($source) t ORDER BY 1"

    private suspend fun TestServer.read(sql: String): List<String> =
        database.superuser.fetchAll(Statement.create(sql)).getOrThrow().map { it.get("line").asString() }

    private suspend fun TestServer.assertDaysAreTheLedgers(step: String) {
        val ledger = read(
            lines(
                "SELECT org_id, CAST(at AT TIME ZONE 'UTC' AS DATE) AS day, member_id, workflow_id, " +
                    "SUM(cost_micros) AS cost FROM usage_events GROUP BY 1, 2, 3, 4 HAVING SUM(cost_micros) <> 0",
            ),
        )
        val days = read(lines("SELECT org_id, day, member_id, workflow_id, cost_micros AS cost FROM usage_spend_days"))
        assertTrue(ledger.isNotEmpty(), step)
        assertEquals(ledger, days, step)
    }

    // Calls of org A over three days around midnight UTC, by two members and nobody, in two workflows or none; one in
    // five costs nothing. Each batch repeats its keys, and the second batch meets the first's.
    private fun batch(offset: Int) = List(40) { i ->
        val n = i + offset
        modelEvent(
            costMicros = if (n % 5 == 0) 0L else n * 10L,
            memberId = listOf(TestCallers.adminA.memberId, TestCallers.memberA.memberId, null)[n % 3],
            at = TestCallers.epoch - (n * 90).minutes,
        ).copy(workflowId = if (n % 4 == 1) null else "w-${n % 2}")
    }

    @Test
    fun theSpendOfTheDaysFollowsEveryWriteOfTheLedger() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        repository.insert(TestCallers.orgA, batch(0))
        repository.insert(TestCallers.orgA, batch(20))
        server.assertDaysAreTheLedgers("two batches, the second adding to the first's days")

        repository.settle(modelEvent(700, at = TestCallers.epoch - 2.hours).copy(workflowId = "w-0"))
        repository.insert(TestCallers.orgB, listOf(modelEvent(5, memberId = null, orgId = TestCallers.orgB)))
        server.assertDaysAreTheLedgers("a settled call, another organization")

        val delete = Statement.create("DELETE FROM usage_events WHERE workflow_id = 'w-1'")
        assertTrue(server.driver.executeInOrg(TestCallers.orgA, delete).getOrThrow() > 0)
        server.assertDaysAreTheLedgers("a delete under the organization's policy")

        assertTrue(repository.purge(TestCallers.epoch - 1.days, 1_000) > 0)
        server.assertDaysAreTheLedgers("the retention purge, run by the router")
        val zero = server.database.superuser.fetchAll(
            Statement.create("SELECT COUNT(*) AS n FROM usage_spend_days WHERE cost_micros = 0"),
        ).getOrThrow().first().get("n").asLong()
        assertEquals(0L, zero, "A day's spend that reaches zero is dropped")

        val owner = server.database.superuser
        owner.execute("UPDATE usage_events SET cost_micros = cost_micros + 3, workflow_id = 'moved' " +
            "WHERE cost_micros % 2 = 1").getOrThrow()
        server.assertDaysAreTheLedgers("an update by another role than the organization's")

        owner.execute("UPDATE usage_events SET cost_micros = 0 WHERE workflow_id = 'moved' " +
            "AND org_id = CAST('${TestCallers.orgA}' AS uuid)").getOrThrow()
        server.assertDaysAreTheLedgers("an update that brings calls to zero")

        // The backfill as version 10 runs it: by the router, under the ledger's forced policies.
        owner.execute("DELETE FROM usage_spend_days").getOrThrow()
        server.database.owner.transaction {
            execute("SET LOCAL ROLE ostrom_router").getOrThrow()
            execute(SPEND_DAYS_OF_THE_LEDGER).getOrThrow()
        }
        server.assertDaysAreTheLedgers("the days version 10 writes from the ledger")

        assertFails("An organization never writes its days") {
            server.driver.executeInOrg(
                TestCallers.orgA,
                Statement.create("UPDATE usage_spend_days SET cost_micros = 1"),
            ).getOrThrow()
        }
        val seenByB = server.driver.fetchInOrg(
            TestCallers.orgB,
            Statement.create("SELECT COUNT(*) AS n FROM usage_spend_days"),
        ).getOrThrow().first().get("n").asLong()
        assertEquals(1L, seenByB, "An organization reads only its own days")

        owner.execute("TRUNCATE usage_events").getOrThrow()
        val left = owner.fetchAll(Statement.create("SELECT COUNT(*) AS n FROM usage_spend_days")).getOrThrow()
        assertEquals(0L, left.first().get("n").asLong(), "A truncated ledger has no days")
    }

    /** What the calls of org A recorded since [since] cost, added up from the ledger itself; [filter] on them. */
    private suspend fun TestServer.ledgerSince(since: Instant, filter: String = ""): Long =
        database.superuser.fetchAll(
            Statement.create(
                "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
                    "WHERE org_id = CAST('${TestCallers.orgA}' AS uuid) AND at >= :since$filter",
            ).bind("since", since),
        ).getOrThrow().first().get("spent").asLong()

    @Test
    fun theSpendReadsGiveWhatTheCallsAddUpToFromAMidnightOrAnyInstant() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        repository.insert(TestCallers.orgA, batch(0))
        repository.insert(TestCallers.orgA, batch(20))
        repository.insert(TestCallers.orgB, listOf(modelEvent(5, memberId = null, orgId = TestCallers.orgB)))
        val midnight = Instant.fromEpochSeconds(TestCallers.epoch.epochSeconds / DAY * DAY)
        val member = TestCallers.memberA.memberId
        val midday = midnight - 1.days + 7.hours + 30.minutes
        for (since in listOf(midnight, midnight - 1.days, midday, midnight + 1.days)) {
            assertEquals(server.ledgerSince(since), repository.spentMicros(TestCallers.orgA, null, since), "$since")
            assertEquals(
                server.ledgerSince(since, " AND member_id = CAST('$member' AS uuid)"),
                repository.spentMicros(TestCallers.orgA, member, since),
                "$since, a member",
            )
            assertEquals(
                server.ledgerSince(since, " AND workflow_id = 'w-0'"),
                repository.workflowSpentMicros(TestCallers.orgA, "w-0", since),
                "$since, a workflow",
            )
        }
        assertTrue(server.ledgerSince(midday) > 0)
    }

    private companion object {
        const val DAY = 86_400L
    }
}
