// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.CronClaim
import run.ostrom.server.automations.domain.ParkedRun
import run.ostrom.server.automations.domain.PayloadProblem
import run.ostrom.server.automations.domain.RunSource
import run.ostrom.server.automations.domain.RunStatus
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.fakes.webhookDefinition
import run.ostrom.server.automations.infrastructure.STARTED_SESSIONS_SQL
import run.ostrom.server.automations.infrastructure.inTenant
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the automations repository's SQL. The routes are in [AutomationsApiTest]. */
class AutomationsPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    private fun automation(owner: Uuid = ALICE, name: String = "Nightly", org: Uuid = ORG_A, at: Instant = t0) =
        Automation(Uuid.random(), org, owner.toString(), cronDefinition(name), false, 1, 1, null, null, at, at)

    private fun run(automation: Automation, key: String, at: Instant = t0, status: RunStatus = RunStatus.QUEUED) =
        AutomationRun(
            Uuid.random(), automation.id, automation.orgId, RunSource.WEBHOOK, status, 1, key,
            problems = listOf(PayloadProblem("$.a", "required")), createdAt = at, updatedAt = at,
        )

    @Test
    fun automationsAreCreatedWithinTheLimitListedAndChangedAtTheirVersion() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val first = automation()
        assertTrue(repository.createWithinLimit(first, 2))
        val second = automation(name = "Second", at = t0 + 1.minutes).copy(definition = webhookDefinition("Second"))
        assertTrue(repository.createWithinLimit(second, 2))
        assertFalse(repository.createWithinLimit(automation(name = "Third"), 2))
        assertTrue(repository.createWithinLimit(automation(BOB, name = "Bob's"), 2))
        assertTrue(repository.createWithinLimit(automation(org = ORG_B), 2))
        assertEquals(first, repository.get(ORG_A, first.id))
        assertNull(repository.get(ORG_B, first.id))
        assertEquals(second, repository.getById(second.id))
        assertNull(repository.getById(Uuid.random()))
        assertEquals(3, repository.list(ORG_A, null, null, 10).size)
        assertEquals(listOf(second.id, first.id), repository.list(ORG_A, ALICE.toString(), null, 10).map { it.id })
        assertEquals(listOf(first.id), repository.list(ORG_A, ALICE.toString(), second.id, 10).map { it.id })
        val changed = first.copy(
            definition = cronDefinition("Renamed"),
            enabled = true,
            version = 2,
            secretGeneration = 2,
            previousSecretValidUntil = t0 + 10.minutes,
            nextRunAt = t0 + 1.minutes,
            updatedAt = t0 + 1.minutes,
        )
        assertEquals(changed, repository.update(changed, first))
        assertNull(repository.update(changed.copy(version = 3), first))
        assertEquals(changed, repository.get(ORG_A, first.id))
        assertTrue(repository.delete(ORG_A, first.id))
        assertFalse(repository.delete(ORG_A, first.id))
        assertFalse(repository.delete(ORG_B, second.id))
    }

    @Test
    fun runsAreDeduplicatedCountedListedAndExpired() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val automation = automation().also { repository.createWithinLimit(it, 20) }
        val first = run(automation, "k1")
        assertEquals(first, repository.insertRun(first))
        assertEquals(first, repository.insertRun(run(automation, "k1")))
        assertEquals(first, repository.findRunByDelivery(ORG_A, automation.id, "k1"))
        assertNull(repository.findRunByDelivery(ORG_A, automation.id, "k2"))
        val started = first.copy(status = RunStatus.STARTED, sessionId = "s1", updatedAt = t0 + 1.minutes)
        repository.updateRun(started)
        val second = repository.insertRun(run(automation, "k2", t0 + 1.minutes))
        val failed = second.copy(status = RunStatus.FAILED, reason = "cloud_session_limit")
        repository.updateRun(failed)
        assertEquals(listOf(failed, started), repository.listRuns(ORG_A, automation.id, null, 10))
        assertEquals(listOf(started), repository.listRuns(ORG_A, automation.id, second.id, 10))
        assertEquals(emptyList(), repository.listRuns(ORG_B, automation.id, null, 10))
        val sources = setOf(RunSource.WEBHOOK)
        assertEquals(2, repository.countRuns(ORG_A, automation.id, t0 - 1.minutes, sources, RunStatus.entries.toSet()))
        assertEquals(1, repository.countRuns(ORG_A, automation.id, t0, sources, RunStatus.entries.toSet()))
        assertEquals(1, repository.countRuns(ORG_A, automation.id, t0 - 1.minutes, sources, setOf(RunStatus.STARTED)))
        val cronOnly = setOf(RunSource.CRON)
        assertEquals(0, repository.countRuns(ORG_A, automation.id, t0 - 1.minutes, cronOnly, setOf(RunStatus.STARTED)))
        assertEquals("s1", repository.latestSession(ORG_A, automation.id, second.id))
        assertNull(repository.latestSession(ORG_A, automation.id, first.id))
        val queued = repository.insertRun(run(automation, "k3", t0 + 2.minutes))
        repository.failQueuedBefore(t0 + 3.minutes, t0 + 4.minutes)
        val interrupted = repository.findRunByDelivery(ORG_A, automation.id, "k3")
        val expected = queued.copy(status = RunStatus.FAILED, reason = "interrupted", updatedAt = t0 + 4.minutes)
        assertEquals(expected, interrupted)
        repository.deleteRunsBefore(t0 + 1.minutes)
        assertEquals(2, repository.listRuns(ORG_A, automation.id, null, 10).size)
        repository.delete(ORG_A, automation.id)
        assertNull(repository.findRunByDelivery(ORG_A, automation.id, "k2"))
    }

    @Test
    fun aRunWaitsForCapacityWithItsPromptTakenOnceAndDroppedByAnyEnd() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val automation = automation().also { a -> repository.createWithinLimit(a, 20) }
        val first = repository.insertRun(run(automation, "w1"))
        repository.park(ORG_A, first.id, "Fix it")
        repository.insertRun(run(automation, "w2", t0 + 1.minutes))
        assertEquals(listOf(ParkedRun(automation, first)), repository.parked(10))
        assertEquals(emptyList(), repository.parked(0))
        val ofB = automation(org = ORG_B).also { a -> repository.createWithinLimit(a, 20) }
        val third = repository.insertRun(run(ofB, "w3", t0 + 2.minutes))
        repository.park(ORG_B, third.id, "Other")
        assertEquals(setOf(first.id, third.id), repository.parked(10).map { p -> p.run.id }.toSet())
        assertEquals(1, repository.parked(1).size)
        // The prompt leaves with the first taker, and only for its own organization.
        assertNull(repository.takeParked(ORG_B, first.id))
        assertEquals("Fix it", repository.takeParked(ORG_A, first.id))
        assertNull(repository.takeParked(ORG_A, first.id))
        // A run that is no longer queued keeps no prompt, and gets none.
        val ended = repository.insertRun(run(automation, "w4", t0 + 3.minutes))
        repository.park(ORG_A, ended.id, "Soon")
        assertTrue(repository.updateRun(ended.copy(status = RunStatus.STARTED, sessionId = "s")))
        assertNull(repository.takeParked(ORG_A, ended.id))
        repository.park(ORG_A, ended.id, "Late")
        assertNull(repository.takeParked(ORG_A, ended.id))
        // Failing the runs queued too long drops the prompts and names the wait.
        val waiting = repository.insertRun(run(automation, "w5", t0 + 4.minutes))
        repository.park(ORG_A, waiting.id, "Slow")
        val lost = repository.insertRun(run(automation, "w6", t0 + 4.minutes))
        repository.failQueuedBefore(t0 + 5.minutes, t0 + 6.minutes)
        assertEquals("capacity_timeout", repository.findRun(ORG_A, automation.id, waiting.id)?.reason)
        assertEquals("interrupted", repository.findRun(ORG_A, automation.id, lost.id)?.reason)
        assertNull(repository.takeParked(ORG_A, waiting.id))
    }

    @Test
    fun aCancelEndsARunOnlyFromTheStatusItSawAndALateStartDoesNotUndoIt() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val automation = automation().also { a -> repository.createWithinLimit(a, 20) }
        val queued = repository.insertRun(run(automation, "c1"))
        val cancelled = queued.copy(status = RunStatus.CANCELLED, reason = "cancelled", updatedAt = t0 + 1.minutes)
        assertFalse(repository.endRun(cancelled, RunStatus.STARTED))
        assertEquals(queued, repository.findRun(ORG_A, automation.id, queued.id))
        assertTrue(repository.endRun(cancelled, RunStatus.QUEUED))
        assertEquals(cancelled, repository.findRun(ORG_A, automation.id, queued.id))
        assertFalse(repository.endRun(cancelled, RunStatus.QUEUED))
        assertFalse(repository.updateRun(queued.copy(status = RunStatus.STARTED, sessionId = "late")))
        assertEquals(cancelled, repository.findRun(ORG_A, automation.id, queued.id))
        assertNull(repository.findRun(ORG_B, automation.id, queued.id))
        assertNull(repository.findRun(ORG_A, Uuid.random(), queued.id))
        // A cancelled run's prompt is dropped with it.
        val waiting = repository.insertRun(run(automation, "c2", t0 + 2.minutes))
        repository.park(ORG_A, waiting.id, "Never")
        val dropped = waiting.copy(status = RunStatus.CANCELLED, reason = "cancelled")
        assertTrue(repository.endRun(dropped, RunStatus.QUEUED))
        assertNull(repository.takeParked(ORG_A, waiting.id))
    }

    @Test
    fun theWorkingSessionsOfAnOwnerAreReadNewestFirstOnTheirOwnIndex() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val automation = automation().also { a -> repository.createWithinLimit(a, 20) }
        val bobs = automation(BOB, "Bob's").also { a -> repository.createWithinLimit(a, 20) }
        suspend fun started(of: Automation, key: String, session: String?, at: Instant) {
            val stored = repository.insertRun(run(of, key, at))
            repository.updateRun(stored.copy(status = RunStatus.STARTED, sessionId = session))
        }
        started(automation, "s1", "sess-1", t0)
        started(automation, "s2", "sess-2", t0 + 5.minutes)
        started(automation, "s3", null, t0 + 6.minutes)
        started(bobs, "s4", "sess-3", t0 + 7.minutes)
        repository.insertRun(run(automation, "s5", t0 + 8.minutes))
        val owner = ALICE.toString()
        assertEquals(listOf("sess-2", "sess-1"), repository.startedSessions(ORG_A, owner, t0 - 1.minutes, 10))
        assertEquals(listOf("sess-2"), repository.startedSessions(ORG_A, owner, t0, 10))
        assertEquals(listOf("sess-2"), repository.startedSessions(ORG_A, owner, t0 - 1.minutes, 1))
        assertEquals(listOf("sess-3"), repository.startedSessions(ORG_A, BOB.toString(), t0, 10))
        assertEquals(emptyList(), repository.startedSessions(ORG_B, owner, t0 - 1.minutes, 10))
        it.database.owner.execute("ANALYZE automations_runs").getOrThrow() // the serving role owns no table
        val plan = it.driver.inTenant(ORG_A) {
            execute("SET LOCAL enable_seqscan = off").getOrThrow()
            val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $STARTED_SESSIONS_SQL")
                .bind("org_id", ORG_A).bind("since", t0).bind("owner", owner).bind("limit", 100)
            fetchAll(explain).getOrThrow().joinToString("\n") { row -> row.get("QUERY PLAN").asString() }
        }
        println("-- the started runs of an owner (each trigger over the cap's reach)\n$plan")
        assertFalse("Seq Scan on automations_runs" in plan, plan)
        assertTrue("automations_runs_" in plan, plan)
    }

    @Test
    fun anotherOrganizationsRowsAreNeitherSeenNorWritten() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val ofA = automation().also { automation -> repository.createWithinLimit(automation, 20) }
        repository.insertRun(run(ofA, "k1"))
        suspend fun count(org: Uuid, table: String) = it.driver.inTenant(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
        }
        for (table in listOf("automations_automations", "automations_runs")) {
            assertEquals(1L, count(ORG_A, table), table)
            // No WHERE org_id: the policy alone hides organization A's rows.
            assertEquals(0L, count(ORG_B, table), table)
        }
        // A write for another organization is refused, and no run of B points at an automation of A.
        assertFailsWith<Exception> {
            it.driver.inTenant(ORG_B) {
                execute(
                    Statement.create(
                        "INSERT INTO automations_automations (id, org_id, owner, name, trigger_kind, definition, " +
                            "enabled, version, secret_generation, created_at, updated_at) VALUES (:id, :org, 'a', " +
                            "'n', 'cron', '{}', false, 1, 1, now(), now())",
                    ).bind("id", Uuid.random()).bind("org", ORG_A),
                ).getOrThrow()
            }
        }
        assertFailsWith<Exception> { repository.insertRun(run(ofA, "k2").copy(orgId = ORG_B)) }
        assertNull(repository.get(ORG_B, ofA.id))
        assertEquals(emptyList(), repository.listRuns(ORG_B, ofA.id, null, 10))
    }

    @Test
    fun aDueCronIsClaimedOnceWithItsNextSlotAndRun() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val due = automation().copy(enabled = true, nextRunAt = t0)
        val skipped = automation(name = "Skipped").copy(enabled = true, nextRunAt = t0 - 1.minutes)
        val later = automation(name = "Later").copy(enabled = true, nextRunAt = t0 + 1.minutes)
        val paused = automation(name = "Paused").copy(nextRunAt = t0)
        listOf(due, skipped, later, paused).forEach { automation -> repository.createWithinLimit(automation, 20) }
        val seen = mutableListOf<Pair<Uuid, Instant>>()
        val claimed = repository.claimDue(t0, 10) { automation, slot ->
            seen += automation.id to slot
            val run = run(automation, "cron:${slot.epochSeconds}").copy(slotAt = slot)
            CronClaim(t0 + 1.minutes, run.takeIf { automation.id == due.id })
        }
        assertEquals(setOf(due.id to t0, skipped.id to t0 - 1.minutes), seen.toSet())
        assertEquals(listOf(due.id), claimed.map { claim -> claim.automation.id })
        assertEquals(t0, claimed.single().slot)
        assertEquals(RunStatus.QUEUED, claimed.single().run.status)
        assertEquals(t0, repository.findRunByDelivery(ORG_A, due.id, "cron:${t0.epochSeconds}")?.slotAt)
        assertEquals(t0 + 1.minutes, repository.get(ORG_A, due.id)?.nextRunAt)
        assertEquals(t0 + 1.minutes, repository.get(ORG_A, skipped.id)?.nextRunAt)
        assertEquals(emptyList(), repository.claimDue(t0, 10) { _, _ -> CronClaim(null, null) })
        val job = Job()
        AutomationsModule.start(CoroutineScope(job), it.koin)
        job.cancel()
    }
}
