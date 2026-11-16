// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.CronClaim
import run.ostrom.server.automations.domain.PayloadProblem
import run.ostrom.server.automations.domain.RunSource
import run.ostrom.server.automations.domain.RunStatus
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.fakes.webhookDefinition
import run.ostrom.server.automations.fakes.workflowDefinition
import run.ostrom.server.automations.infrastructure.AutomationsAutomations
import run.ostrom.server.automations.infrastructure.AutomationsRuns
import run.ostrom.server.automations.infrastructure.automationStartingQuery
import run.ostrom.server.automations.infrastructure.followedPickQuery
import run.ostrom.server.automations.infrastructure.startedSessionsQuery
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.workflows.AutomationUsage
import run.ostrom.server.testing.assertApiCatalogue
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
    fun theCatalogueDescribesEachRouteOnce() = serverTest(listOf(AutomationsModule)) { server ->
        server.assertApiCatalogue(AutomationsModule)
    }

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
        assertEquals(listOf(first), repository.parked(10))
        assertEquals(emptyList(), repository.parked(0))
        val ofB = automation(org = ORG_B).also { a -> repository.createWithinLimit(a, 20) }
        val third = repository.insertRun(run(ofB, "w3", t0 + 2.minutes))
        repository.park(ORG_B, third.id, "Other")
        assertEquals(setOf(first.id, third.id), repository.parked(10).map { p -> p.id }.toSet())
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
        it.database.superuserOutsideTransaction("ANALYZE automations_runs") // the serving role owns no table
        val plan = it.koin.get<JdbcDatabase>().inOrg(ORG_A) {
            exec("SET LOCAL enable_seqscan = off")
            plan { startedSessionsQuery(ORG_A, owner, t0, 100) }
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
        val jdbc = it.koin.get<JdbcDatabase>()
        suspend fun count(org: Uuid, table: org.jetbrains.exposed.v1.core.Table) =
            jdbc.inOrg(org) { table.selectAll().count() }
        for (table in listOf(AutomationsAutomations, AutomationsRuns)) {
            assertEquals(1L, count(ORG_A, table), table.tableName)
            // No WHERE org_id: the policy alone hides organization A's rows.
            assertEquals(0L, count(ORG_B, table), table.tableName)
        }
        // A write for another organization is refused, and no run of B points at an automation of A.
        assertFailsWith<Exception> {
            jdbc.inOrg(ORG_B) {
                AutomationsAutomations.insert { row ->
                    row[AutomationsAutomations.id] = Uuid.random()
                    row[AutomationsAutomations.orgId] = ORG_A
                    row[AutomationsAutomations.owner] = "a"
                    row[AutomationsAutomations.name] = "n"
                    row[AutomationsAutomations.triggerKind] = "cron"
                    row[AutomationsAutomations.definition] = "{}"
                    row[AutomationsAutomations.enabled] = false
                    row[AutomationsAutomations.version] = 1
                    row[AutomationsAutomations.secretGeneration] = 1
                    row[AutomationsAutomations.createdAt] = t0
                    row[AutomationsAutomations.updatedAt] = t0
                }
            }
        }
        assertFailsWith<Exception> { repository.insertRun(run(ofA, "k2").copy(orgId = ORG_B)) }
        assertNull(repository.get(ORG_B, ofA.id))
        assertEquals(emptyList(), repository.listRuns(ORG_B, ofA.id, null, 10))
    }

    @Test
    fun aClaimThatPausesLeavesTheAutomationDisabledWithoutANextSlot() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val broken = automation(name = "Broken").copy(enabled = true, nextRunAt = t0)
        val healthy = automation(name = "Healthy").copy(enabled = true, nextRunAt = t0)
        listOf(broken, healthy).forEach { automation -> repository.createWithinLimit(automation, 20) }
        repository.claimDue(t0, 10) { automation, _ ->
            if (automation.id == broken.id) CronClaim(null, null, pause = true) else CronClaim(t0 + 1.minutes, null)
        }
        val paused = repository.get(ORG_A, broken.id)
        assertEquals(false, paused?.enabled)
        assertNull(paused?.nextRunAt)
        assertEquals(true, repository.get(ORG_A, healthy.id)?.enabled, "A claim that does not pause keeps it enabled")
        assertEquals(emptyList(), repository.claimDue(t0, 10) { _, _ -> error("nothing is due") })
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

    @Test
    fun workflowActionsAreStoredFollowedAndFoundOnTheirIndexes() = serverTest(listOf(AutomationsModule)) {
        val repository = it.koin.get<AutomationsRepository>()
        val triage = automation().copy(definition = workflowDefinition())
        repository.createWithinLimit(triage, 20)
        repository.createWithinLimit(automation(BOB, "Bob's"), 20)
        val ofB = automation(org = ORG_B).copy(definition = workflowDefinition())
        repository.createWithinLimit(ofB, 20)
        val runOfB = repository.insertRun(run(ofB, "b1", t0))
        repository.updateRun(runOfB.copy(status = RunStatus.STARTED, workflowRunId = "wfr_b"))
        assertEquals(triage, repository.get(ORG_A, triage.id))
        assertEquals("Triage", repository.automationStarting(ORG_A, "triage"))
        val usage = it.koin.get<AutomationUsage>()
        assertEquals("Triage", usage.automationStarting(ORG_A, "triage"), "Bound for the compiler")
        assertNull(repository.automationStarting(ORG_A, "other"))
        val renamed = triage.copy(definition = workflowDefinition().copy(action = cronDefinition().action), version = 2)
        repository.update(renamed, triage)
        assertNull(repository.automationStarting(ORG_A, "triage"), "The column follows the definition")
        repository.update(triage.copy(version = 3), renamed)
        val first = repository.insertRun(run(triage, "w1", t0))
        assertTrue(repository.updateRun(first.copy(status = RunStatus.STARTED, workflowRunId = "wfr_1")))
        val second = repository.insertRun(run(triage, "w2", t0 + 1.minutes))
        repository.updateRun(second.copy(status = RunStatus.STARTED, workflowRunId = "wfr_2"))
        repository.insertRun(run(triage, "w3", t0 + 2.minutes))
        // A session run of the time its action was a session one: no workflow run to overlap with.
        val asSession = repository.insertRun(run(triage, "s1", t0 + 3.minutes))
        repository.updateRun(asSession.copy(status = RunStatus.STARTED, sessionId = "ses_1"))
        assertEquals("ses_1", repository.latestSession(ORG_A, triage.id, Uuid.random()))
        assertEquals("wfr_1", repository.findRun(ORG_A, triage.id, first.id)?.workflowRunId)
        assertEquals("wfr_2", repository.latestWorkflowRun(ORG_A, triage.id, Uuid.random()), "Not the session run")
        assertEquals("wfr_1", repository.latestWorkflowRun(ORG_A, triage.id, second.id))
        assertNull(repository.latestWorkflowRun(ORG_A, Uuid.random(), Uuid.random()))
        val followed = repository.followed(t0 + 10.minutes, 10)
        assertEquals(setOf("wfr_1", "wfr_2", "wfr_b"), followed.map { run -> run.workflowRunId }.toSet())
        val once = repository.followed(t0 + 11.minutes, 1)
        assertEquals(setOf(ORG_A, ORG_B), once.map { run -> run.run.orgId }.toSet(), "A limit of each organization")
        val nextOfA = repository.followed(t0 + 12.minutes, 1).single { run -> run.run.orgId == ORG_A }
        val firstOfA = once.single { run -> run.run.orgId == ORG_A }
        assertTrue(nextOfA.run.id != firstOfA.run.id, "The run followed last goes behind the other")
        val ended = first.copy(status = RunStatus.SUCCEEDED, workflowRunId = "wfr_1", updatedAt = t0 + 5.minutes)
        assertTrue(repository.endRun(ended, RunStatus.STARTED))
        val left = repository.followed(t0 + 13.minutes, 10)
        assertEquals(setOf("wfr_2", "wfr_b"), left.map { run -> run.workflowRunId }.toSet())
        for (run in left) {
            assertTrue(repository.endRun(run.run.copy(status = RunStatus.SUCCEEDED), RunStatus.STARTED))
        }
        assertEquals(emptyList(), repository.followed(t0 + 14.minutes, 10), "Nothing is followed once all ended")
        it.database.superuserOutsideTransaction("ANALYZE automations_automations", "ANALYZE automations_runs")
        val jdbc = it.koin.get<JdbcDatabase>()
        suspend fun planOf(query: () -> Query) = jdbc.inOrg(ORG_A) {
            exec("SET LOCAL enable_seqscan = off")
            plan { query() }
        }
        val starting = planOf { automationStartingQuery(ORG_A, "triage") }
        println("-- the automation that starts a workflow (DELETE /api/v1/workflows/{id})\n$starting")
        assertTrue("automations_automations_workflow" in starting, starting)
        val follow = planOf { followedPickQuery(ORG_A, 20) }
        println("-- the started runs of workflow actions (the scheduler)\n$follow")
        assertTrue("automations_runs_followed" in follow, follow)
    }
}
