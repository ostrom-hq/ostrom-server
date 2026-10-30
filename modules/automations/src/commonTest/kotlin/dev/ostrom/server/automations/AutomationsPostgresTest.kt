// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.Automation
import dev.ostrom.server.automations.domain.AutomationRun
import dev.ostrom.server.automations.domain.AutomationsRepository
import dev.ostrom.server.automations.domain.CronClaim
import dev.ostrom.server.automations.domain.PayloadProblem
import dev.ostrom.server.automations.domain.RunSource
import dev.ostrom.server.automations.domain.RunStatus
import dev.ostrom.server.automations.fakes.ALICE
import dev.ostrom.server.automations.fakes.BOB
import dev.ostrom.server.automations.fakes.ORG_A
import dev.ostrom.server.automations.fakes.ORG_B
import dev.ostrom.server.automations.fakes.cronDefinition
import dev.ostrom.server.automations.fakes.webhookDefinition
import dev.ostrom.server.testing.serverTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(first, repository.findRunByDelivery(automation.id, "k1"))
        assertNull(repository.findRunByDelivery(automation.id, "k2"))
        val started = first.copy(status = RunStatus.STARTED, sessionId = "s1", updatedAt = t0 + 1.minutes)
        repository.updateRun(started)
        val second = repository.insertRun(run(automation, "k2", t0 + 1.minutes))
        val failed = second.copy(status = RunStatus.FAILED, reason = "cloud_session_limit")
        repository.updateRun(failed)
        assertEquals(listOf(failed, started), repository.listRuns(ORG_A, automation.id, null, 10))
        assertEquals(listOf(started), repository.listRuns(ORG_A, automation.id, second.id, 10))
        assertEquals(emptyList(), repository.listRuns(ORG_B, automation.id, null, 10))
        val sources = setOf(RunSource.WEBHOOK)
        assertEquals(2, repository.countRuns(automation.id, t0 - 1.minutes, sources, RunStatus.entries.toSet()))
        assertEquals(1, repository.countRuns(automation.id, t0, sources, RunStatus.entries.toSet()))
        assertEquals(1, repository.countRuns(automation.id, t0 - 1.minutes, sources, setOf(RunStatus.STARTED)))
        val cronOnly = setOf(RunSource.CRON)
        assertEquals(0, repository.countRuns(automation.id, t0 - 1.minutes, cronOnly, setOf(RunStatus.STARTED)))
        assertEquals("s1", repository.latestSession(automation.id, second.id))
        assertNull(repository.latestSession(automation.id, first.id))
        val queued = repository.insertRun(run(automation, "k3", t0 + 2.minutes))
        repository.failQueuedBefore(t0 + 3.minutes, t0 + 4.minutes)
        val interrupted = repository.findRunByDelivery(automation.id, "k3")
        val expected = queued.copy(status = RunStatus.FAILED, reason = "interrupted", updatedAt = t0 + 4.minutes)
        assertEquals(expected, interrupted)
        repository.deleteRunsBefore(t0 + 1.minutes)
        assertEquals(2, repository.listRuns(ORG_A, automation.id, null, 10).size)
        repository.delete(ORG_A, automation.id)
        assertNull(repository.findRunByDelivery(automation.id, "k2"))
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
        assertEquals(t0, repository.findRunByDelivery(due.id, "cron:${t0.epochSeconds}")?.slotAt)
        assertEquals(t0 + 1.minutes, repository.get(ORG_A, due.id)?.nextRunAt)
        assertEquals(t0 + 1.minutes, repository.get(ORG_A, skipped.id)?.nextRunAt)
        assertEquals(emptyList(), repository.claimDue(t0, 10) { _, _ -> CronClaim(null, null) })
        val job = Job()
        AutomationsModule.start(CoroutineScope(job), it.koin)
        job.cancel()
    }
}
