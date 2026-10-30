// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.Automation
import dev.ostrom.server.automations.domain.AutomationAction
import dev.ostrom.server.automations.domain.AutomationDefinition
import dev.ostrom.server.automations.domain.AutomationDisabledException
import dev.ostrom.server.automations.domain.AutomationForbiddenException
import dev.ostrom.server.automations.domain.AutomationNotFoundException
import dev.ostrom.server.automations.domain.AutomationRateLimitException
import dev.ostrom.server.automations.domain.Catchup
import dev.ostrom.server.automations.domain.InvalidAutomationException
import dev.ostrom.server.automations.domain.InvalidSignatureException
import dev.ostrom.server.automations.domain.InvalidWebhookPayloadException
import dev.ostrom.server.automations.domain.LaunchResult
import dev.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import dev.ostrom.server.automations.domain.Overlap
import dev.ostrom.server.automations.domain.PayloadProblem
import dev.ostrom.server.automations.domain.RunAutomationsUseCaseImpl
import dev.ostrom.server.automations.domain.RunSource
import dev.ostrom.server.automations.domain.RunStatus
import dev.ostrom.server.automations.domain.WebhookDelivery
import dev.ostrom.server.automations.domain.WebhookReplayException
import dev.ostrom.server.automations.domain.AutomationVariable
import dev.ostrom.server.automations.fakes.InMemoryAutomations
import dev.ostrom.server.automations.fakes.PlainSecrets
import dev.ostrom.server.automations.fakes.RecordingLauncher
import dev.ostrom.server.automations.fakes.TestClock
import dev.ostrom.server.automations.fakes.aliceAuto
import dev.ostrom.server.automations.fakes.bobAuto
import dev.ostrom.server.automations.fakes.cronDefinition
import dev.ostrom.server.automations.fakes.daveAuto
import dev.ostrom.server.automations.fakes.webhookDefinition
import dev.ostrom.server.automations.presentation.toResponse
import dev.ostrom.server.kernel.auth.MemberDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunAutomationsTest {
    private val repository = InMemoryAutomations()
    private val launcher = RecordingLauncher()
    private val clock = TestClock(Instant.parse("2026-10-30T10:00:00Z"))
    private var owners: MemberDirectory = members(true)
    private val manage = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock)
    private val runs get() = RunAutomationsUseCaseImpl(repository, launcher, PlainSecrets, owners, clock)

    private val body = """{"ticket":{"number":"SUP-12"},"priority":"high"}""".encodeToByteArray()

    private suspend fun hook(
        definition: AutomationDefinition = webhookDefinition(),
        enabled: Boolean = true,
    ): Automation {
        val id = manage.create(aliceAuto, definition).automation.id
        return if (enabled) manage.setEnabled(aliceAuto, id, true) else manage.get(aliceAuto, id)
    }

    private suspend fun signed(
        automation: Automation,
        body: ByteArray = this.body,
        generation: Int = automation.secretGeneration,
        at: Instant = clock.now,
        key: String? = null,
    ): WebhookDelivery {
        val timestamp = at.epochSeconds.toString()
        val secret = PlainSecrets.secret(automation.id, generation)
        val signature = PlainSecrets.sign(secret, "$timestamp.".encodeToByteArray() + body).toHexString()
        return WebhookDelivery(timestamp, "v0=00, v1=${signature.uppercase()}", key)
    }

    @Test
    fun aSignedWebhookStartsASessionAsTheOwnerWithItsVariables() = runTest {
        val automation = hook()
        val triggered = runs.trigger(automation.id, signed(automation), body)
        assertFalse(triggered.deduplicated)
        assertEquals(RunStatus.STARTED, triggered.run.status)
        assertEquals("session-1", triggered.run.sessionId)
        assertEquals(RunSource.WEBHOOK, triggered.run.source)
        val request = launcher.launched.single()
        assertEquals(aliceAuto.memberId, request.owner)
        assertEquals(triggered.run.id, request.runId)
        assertEquals("Ticket", request.name)
        assertEquals("Fix ticket SUP-12 (high) for Ticket", request.prompt)
    }

    @Test
    fun everyBadProofAnswersTheSameAndAPausedAutomationOnlyAfterItsProof() = runTest {
        val automation = hook()
        val cron = manage.setEnabled(aliceAuto, manage.create(aliceAuto, cronDefinition()).automation.id, true)
        val good = signed(automation)
        val refused = listOf(
            Uuid.random() to good,
            cron.id to signed(cron),
            automation.id to good.copy(timestamp = null),
            automation.id to good.copy(timestamp = "soon"),
            automation.id to signed(automation, at = clock.now - 301.seconds),
            automation.id to signed(automation, at = clock.now + 301.seconds),
            automation.id to good.copy(signature = null),
            automation.id to good.copy(signature = "v1=abcd"),
            automation.id to signed(automation, generation = 2),
            automation.id to signed(automation, body = "{}".encodeToByteArray()),
        )
        for ((id, delivery) in refused) assertFailsWith<InvalidSignatureException> { runs.trigger(id, delivery, body) }
        runs.trigger(automation.id, signed(automation, at = clock.now - 300.seconds), body)
        val paused = manage.setEnabled(aliceAuto, automation.id, false)
        assertFailsWith<InvalidSignatureException> { runs.trigger(paused.id, good.copy(signature = "v1=00"), body) }
        assertFailsWith<AutomationDisabledException> { runs.trigger(paused.id, signed(paused), body) }
    }

    @Test
    fun theFormerSecretIsAcceptedTenMinutesAfterARotation() = runTest {
        val automation = hook()
        manage.rotateSecret(aliceAuto, automation.id)
        val rotated = repository.automations.getValue(automation.id)
        runs.trigger(automation.id, signed(rotated, generation = 1, key = "a"), body)
        runs.trigger(automation.id, signed(rotated, key = "b"), body)
        clock.now += 10.minutes
        assertFailsWith<InvalidSignatureException> {
            runs.trigger(automation.id, signed(rotated, generation = 1, key = "c"), body)
        }
        runs.trigger(automation.id, signed(rotated, key = "d"), body)
    }

    @Test
    fun theBodyIsAJsonObjectOfAtMost64KiB() = runTest {
        val automation = hook()
        for (bad in listOf("[1]", "nope", "\"text\"", "{oops")) {
            val bytes = bad.encodeToByteArray()
            val delivery = signed(automation, bytes)
            assertFailsWith<InvalidWebhookPayloadException> { runs.trigger(automation.id, delivery, bytes) }
        }
        val large = ("{\"x\":\"" + "y".repeat(64 * 1024) + "\"}").encodeToByteArray()
        val delivery = signed(automation, large)
        assertFailsWith<InvalidWebhookPayloadException> { runs.trigger(automation.id, delivery, large) }
    }

    @Test
    fun aDeliveryIsAcceptedOnceAndAReplayIsRefused() = runTest {
        val automation = hook()
        assertFalse(runs.trigger(automation.id, signed(automation, key = "delivery-1"), body).deduplicated)
        clock.now += 1.minutes
        assertFailsWith<WebhookReplayException> {
            runs.trigger(automation.id, signed(automation, key = "delivery-1"), body)
        }
        // Without a delivery id, the signed request is the id: the same request again is a replay (S2-50)…
        val captured = signed(automation)
        runs.trigger(automation.id, captured, body)
        clock.now += 4.minutes
        assertFailsWith<WebhookReplayException> { runs.trigger(automation.id, captured, body) }
        // …and past 5 minutes its signed timestamp no longer verifies at all.
        clock.now += 2.minutes
        assertFailsWith<InvalidSignatureException> { runs.trigger(automation.id, captured, body) }
        // The same body signed again is a new delivery.
        runs.trigger(automation.id, signed(automation), body)
        assertEquals(3, launcher.launched.size)
        assertFailsWith<InvalidWebhookPayloadException> {
            runs.trigger(automation.id, signed(automation, key = "with space"), body)
        }
    }

    @Test
    fun thirtyRequestsAMinuteAreAcceptedThenTheDayBudgetSkips() = runTest {
        val automation = hook(webhookDefinition().copy(overlap = Overlap.ALLOW, maxRunsPerDay = 31))
        repeat(30) { runs.trigger(automation.id, signed(automation, key = "k$it"), body) }
        val limited = assertFailsWith<AutomationRateLimitException> {
            runs.trigger(automation.id, signed(automation, key = "k30"), body)
        }
        assertEquals("At most 30 requests a minute.", limited.message)
        clock.now += 1.minutes
        runs.trigger(automation.id, signed(automation, key = "k31"), body)
        val budget = assertFailsWith<AutomationRateLimitException> {
            runs.trigger(automation.id, signed(automation, key = "k32"), body)
        }
        assertEquals("This automation used its runs of the day.", budget.message)
        val skipped = repository.findRunByDelivery(automation.orgId, automation.id, "delivery:k32")
        assertEquals(RunStatus.SKIPPED_BUDGET, skipped?.status)
        assertEquals(31, launcher.launched.size)
    }

    @Test
    fun aPayloadThatDoesNotFitIsRecordedWithoutASession() = runTest {
        val automation = hook()
        val bytes = """{"ticket":{"number":"SUP-123456789"}}""".encodeToByteArray()
        val triggered = runs.trigger(automation.id, signed(automation, bytes), bytes)
        assertEquals(RunStatus.INVALID_PAYLOAD, triggered.run.status)
        assertEquals(listOf(PayloadProblem("$.ticket.number", "max_length")), triggered.run.problems)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun aRunWaitsForNothingWhenItsOwnerLeftOrItsLastSessionStillWorks() = runTest {
        val automation = hook()
        val first = runs.trigger(automation.id, signed(automation, key = "1"), body).run
        launcher.working += first.sessionId!!
        val second = runs.trigger(automation.id, signed(automation, key = "2"), body).run
        assertEquals(RunStatus.SKIPPED_OVERLAP, second.status)
        launcher.working.clear()
        assertEquals(RunStatus.STARTED, runs.trigger(automation.id, signed(automation, key = "3"), body).run.status)
        owners = members(false)
        val orphan = runs.trigger(automation.id, signed(automation, key = "4"), body).run
        assertEquals(RunStatus.FAILED, orphan.status)
        assertEquals("owner_unavailable", orphan.reason)
        val unchecked = RunAutomationsUseCaseImpl(repository, launcher, PlainSecrets, null, clock)
        val fifth = unchecked.trigger(automation.id, signed(automation, key = "5"), body).run
        assertEquals(RunStatus.STARTED, fifth.status)
    }

    @Test
    fun aRefusedOrFailingLaunchFailsTheRunAndACancellationIsNeverSwallowed() = runTest {
        val automation = hook(webhookDefinition().copy(overlap = Overlap.ALLOW))
        launcher.next = { LaunchResult.Refused("cloud_session_limit") }
        val refused = runs.trigger(automation.id, signed(automation, key = "1"), body).run
        assertEquals(RunStatus.FAILED, refused.status)
        assertEquals("cloud_session_limit", refused.reason)
        launcher.next = { error("prompt text") }
        val failing = runs.trigger(automation.id, signed(automation, key = "2"), body).run
        assertEquals("launch_failed:IllegalStateException", failing.reason)
        launcher.next = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { runs.trigger(automation.id, signed(automation, key = "3"), body) }
    }

    @Test
    fun theOwnerTestsTheSavedDefinitionWithAKey() = runTest {
        val automation = hook(enabled = false)
        val payload = buildJsonObject { putJsonObject("ticket") { put("number", "SUP-1") } }
        assertFailsWith<AutomationForbiddenException> { runs.test(daveAuto, automation.id, payload, "k") }
        assertFailsWith<AutomationNotFoundException> { runs.test(bobAuto, automation.id, payload, "k") }
        assertFailsWith<InvalidAutomationException> { runs.test(aliceAuto, automation.id, null, "k") }
        assertFailsWith<InvalidAutomationException> { runs.test(aliceAuto, automation.id, payload, null) }
        assertFailsWith<InvalidAutomationException> { runs.test(aliceAuto, automation.id, payload, "a b") }
        val tested = runs.test(aliceAuto, automation.id, payload, "k")
        assertEquals(RunSource.TEST, tested.run.source)
        assertEquals("Fix ticket SUP-1 () for Ticket", launcher.launched.single().prompt)
        assertTrue(runs.test(aliceAuto, automation.id, payload, "k").deduplicated)
        val cron = manage.create(aliceAuto, cronDefinition()).automation
        assertFailsWith<InvalidAutomationException> { runs.test(aliceAuto, cron.id, payload, "k") }
        assertEquals(RunStatus.STARTED, runs.test(aliceAuto, cron.id, null, "k").run.status)
        assertEquals("Summarize 2026-10-30 at 11:00", launcher.launched.last().prompt)
    }

    @Test
    fun testsDoNotCountTowardsTheDayBudget() = runTest {
        val automation = hook(webhookDefinition().copy(overlap = Overlap.ALLOW, maxRunsPerDay = 1))
        val payload = buildJsonObject { putJsonObject("ticket") { put("number", "SUP-1") } }
        repeat(3) { runs.test(aliceAuto, automation.id, payload, "t$it") }
        assertEquals(RunStatus.STARTED, runs.trigger(automation.id, signed(automation), body).run.status)
    }

    @Test
    fun aDryRunRendersMasksAndTellsWhatWouldSkip() = runTest {
        val automation = hook(enabled = false)
        val payload = buildJsonObject {
            putJsonObject("ticket") { put("number", "SUP-1") }
            put("priority", "p1")
        }
        val dry = runs.dryRun(aliceAuto, automation.id, payload)
        assertEquals("Fix ticket SUP-1 (p1) for Ticket", dry.renderedPrompt)
        assertEquals(listOf("SUP-1", "••••"), dry.variables.map { it.value })
        assertEquals("disabled", dry.wouldSkip)
        assertEquals(emptyList(), dry.nextRuns)
        manage.setEnabled(aliceAuto, automation.id, true)
        assertNull(runs.dryRun(aliceAuto, automation.id, payload).wouldSkip)
        val missing = runs.dryRun(aliceAuto, automation.id, buildJsonObject {})
        assertEquals(listOf(null, "••••"), missing.variables.map { it.value })
        assertEquals(listOf(PayloadProblem("$.ticket.number", "required")), missing.problems)
        val started = runs.trigger(automation.id, signed(automation), body).run
        launcher.working += started.sessionId!!
        assertEquals("overlap", runs.dryRun(aliceAuto, automation.id, payload).wouldSkip)
        val strict = hook(webhookDefinition(name = "Strict").copy(maxRunsPerDay = 1))
        runs.trigger(strict.id, signed(strict), body)
        assertEquals("budget", runs.dryRun(aliceAuto, strict.id, payload).wouldSkip)
        // A sensitive value that is missing shows as missing, not masked.
        val secret = AutomationVariable("token", "$.token", sensitive = true)
        val guarded = hook(
            webhookDefinition(name = "Guarded").copy(
                action = AutomationAction(prompt = "Use {{token}}"),
                variables = listOf(secret),
            ),
        )
        assertEquals(listOf(null), runs.dryRun(aliceAuto, guarded.id, buildJsonObject {}).variables.map { it.value })
        val cron = manage.create(aliceAuto, cronDefinition()).automation
        assertEquals(3, runs.dryRun(aliceAuto, cron.id, null).nextRuns.size)
    }

    @Test
    fun theHistoryIsTheOwnersOrAnAdministratorsPageByPage() = runTest {
        val automation = hook(webhookDefinition().copy(overlap = Overlap.ALLOW))
        val ids = (1..3).map { runs.trigger(automation.id, signed(automation, key = "$it"), body).run.id }
        val page = runs.runs(aliceAuto, automation.id, null, 2)
        assertEquals(ids.reversed().take(2), page.items.map { it.id })
        assertEquals(ids[1], page.next)
        assertEquals(listOf(ids[0]), runs.runs(daveAuto, automation.id, page.next, null).items.map { it.id })
        assertFailsWith<AutomationNotFoundException> { runs.runs(bobAuto, automation.id, null, null) }
        assertFailsWith<InvalidAutomationException> { runs.runs(aliceAuto, automation.id, null, 0) }
        assertFailsWith<InvalidAutomationException> { runs.runs(aliceAuto, automation.id, null, 101) }
    }

    @Test
    fun aDueCronRunsOnceAtItsSlotThenWaitsForTheNext() = runTest {
        val cron = manage.setEnabled(aliceAuto, manage.create(aliceAuto, cronDefinition()).automation.id, true)
        assertEquals(0, runs.tick())
        clock.now = Instant.parse("2026-10-30T20:00:30Z")
        assertEquals(1, runs.tick())
        assertEquals("Summarize 2026-10-30 at 21:00", launcher.launched.single().prompt)
        val run = repository.runs.values.single()
        assertEquals(RunSource.CRON, run.source)
        assertEquals(Instant.parse("2026-10-30T20:00:00Z"), run.slotAt)
        assertEquals("2026-10-30T20:00:00Z", run.toResponse().slotAt)
        assertEquals(Instant.parse("2026-10-31T20:00:00Z"), repository.automations.getValue(cron.id).nextRunAt)
        assertEquals(0, runs.tick())
        assertEquals(clock.now - 14.days, repository.deletedBefore)
        assertEquals(clock.now - 30.minutes, repository.failedBefore)
    }

    @Test
    fun aLateSlotRunsOnceWithinTheGraceAndNeverWithSkip() = runTest {
        fun definition(catchup: String, grace: Int) = cronDefinition(name = catchup + grace).let {
            it.copy(
                trigger = it.trigger.copy(
                    catchup = Catchup.valueOf(catchup),
                    catchupGraceMinutes = grace,
                ),
            )
        }
        val within = manage.create(aliceAuto, definition("ONCE", 360)).automation.id
        val beyond = manage.create(aliceAuto, definition("ONCE", 30)).automation.id
        val skip = manage.create(aliceAuto, definition("SKIP", 360)).automation.id
        listOf(within, beyond, skip).forEach { manage.setEnabled(aliceAuto, it, true) }
        // The server was down from 20:00 to 21:00 UTC.
        clock.now = Instant.parse("2026-10-30T21:00:00Z")
        assertEquals(1, runs.tick())
        assertEquals(within, repository.runs.values.single().automationId)
        listOf(within, beyond, skip).forEach {
            assertEquals(Instant.parse("2026-10-31T20:00:00Z"), repository.automations.getValue(it).nextRunAt)
        }
        clock.now = Instant.parse("2026-10-31T20:01:00Z") + 1.hours
        assertEquals(1, runs.tick())
    }
}

private fun members(active: Boolean) = object : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = active
}
