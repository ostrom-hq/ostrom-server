// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationRunEndedException
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.NoWorkflows
import run.ostrom.server.automations.domain.Overlap
import run.ostrom.server.automations.domain.RunAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.RunStatus
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.domain.WorkflowRunState
import run.ostrom.server.automations.domain.WorkflowStart
import run.ostrom.server.automations.domain.WorkflowStartRequest
import run.ostrom.server.automations.domain.WorkflowsStartedByAutomations
import run.ostrom.server.automations.fakes.InMemoryAutomations
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.PlainSecrets
import run.ostrom.server.automations.fakes.RecordingLauncher
import run.ostrom.server.automations.fakes.RecordingWorkflows
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.aliceAuto
import run.ostrom.server.automations.fakes.daveAuto
import run.ostrom.server.automations.fakes.workflowDefinition
import run.ostrom.server.automations.presentation.AutomationActionRequest
import run.ostrom.server.automations.presentation.AutomationRequest
import run.ostrom.server.automations.presentation.toDefinition
import run.ostrom.server.automations.presentation.toResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WorkflowActionTest {
    private val repository = InMemoryAutomations()
    private val launcher = RecordingLauncher()
    private val workflows = RecordingWorkflows().apply {
        inputs["triage"] = listOf(
            WorkflowInputSpec("ticket", required = true, hasDefault = false),
            WorkflowInputSpec("note", required = true, hasDefault = true),
            WorkflowInputSpec("label", required = false, hasDefault = false),
        )
    }
    private val clock = TestClock(Instant.parse("2026-10-30T10:00:00Z"))
    private val manage = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock, workflows)
    private val runs = RunAutomationsUseCaseImpl(repository, launcher, PlainSecrets, null, clock, workflows = workflows)

    private suspend fun refusal(definition: AutomationDefinition): InvalidAutomationException =
        assertFailsWith<InvalidAutomationException> { manage.create(aliceAuto, definition) }

    private fun action(
        prompt: String = "",
        workflowId: String = "triage",
        inputs: Map<String, String> = mapOf("ticket" to "T"),
        repository: String? = null,
    ) = workflowDefinition().copy(
        action = AutomationAction(prompt, workflowId = workflowId, inputs = inputs, repository = repository),
    )

    @Test
    fun aWorkflowActionIsSavedWhenItFitsItsWorkflowsInputs() = runTest {
        val saved = manage.create(aliceAuto, workflowDefinition()).automation
        assertEquals("triage", saved.definition.action.workflowId)
        assertEquals(mapOf("ticket" to "T-{{run.date}}", "note" to ""), saved.definition.action.inputs)
        assertEquals("acme/api", saved.definition.action.repository)
        val changed = manage.update(aliceAuto, saved.id, 1, action(inputs = mapOf("ticket" to "T", "label" to "x")))
        assertEquals(2, changed.version)
    }

    @Test
    fun aWorkflowActionThatDoesNotFitIsRefusedInvalidAutomationNamingTheInput() = runTest {
        val refused = mapOf(
            action(prompt = "Hello") to "action.prompt: a workflow action has an empty prompt.",
            action(workflowId = "-bad") to "action.workflowId: invalid id.",
            action(inputs = (1..17).associate { "i$it" to "x" }) to "action.inputs: at most 16 inputs.",
            action(inputs = mapOf("9lives" to "x")) to "action.inputs.9lives: invalid input id.",
            action(inputs = mapOf("ticket" to "x".repeat(4097))) to "action.inputs.ticket: at most 4096 characters.",
            action(inputs = mapOf("ticket" to "{{ ticket.id }}")) to
                "action.inputs.ticket: names unknown variables: ticket.id.",
            action(repository = "api") to "action.repository: owner/name.",
            action(workflowId = "gone") to "action.workflowId: no workflow gone that a run may start.",
            action(inputs = mapOf("ticket" to "T", "zzz" to "x")) to "action.inputs.zzz: not an input of the workflow.",
            action(inputs = mapOf("ticket" to " ")) to
                "action.inputs.ticket: required by the workflow, which has no default.",
            action(inputs = mapOf("label" to "x")) to
                "action.inputs.ticket: required by the workflow, which has no default.",
        )
        for ((definition, message) in refused) {
            val error = refusal(definition)
            assertEquals(message, error.message)
            assertEquals("invalid_automation", error.type)
        }
        val saved = manage.create(aliceAuto, workflowDefinition()).automation
        assertFailsWith<InvalidAutomationException> {
            manage.update(aliceAuto, saved.id, 1, action(workflowId = "gone"))
        }
        assertTrue(repository.automations.size == 1, "Nothing refused was stored")
    }

    @Test
    fun aServerThatRunsNoWorkflowsHasNoneForAnActionAndRefusesEveryStart() = runTest {
        val bare = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock)
        val error = assertFailsWith<InvalidAutomationException> { bare.create(aliceAuto, workflowDefinition()) }
        assertEquals("action.workflowId: no workflow triage that a run may start.", error.message)
        val request = WorkflowStartRequest(ORG_A, "alice", Uuid.random(), "triage", emptyMap(), null, "k")
        assertEquals(WorkflowStart.Refused("workflows_unavailable"), NoWorkflows.start(request))
        assertNull(NoWorkflows.state(ORG_A, "wfr_1"))
        assertFalse(NoWorkflows.cancel(ORG_A, "alice", "wfr_1"))
    }

    @Test
    fun aRunStartsTheWorkflowAsTheOwnerUnderItsRunsKeyWithTheRenderedInputs() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition()).automation
        val run = runs.test(aliceAuto, automation.id, null, "t1").run
        assertEquals(RunStatus.STARTED, run.status)
        assertEquals("wfr_1", run.workflowRunId)
        assertNull(run.sessionId)
        val request = workflows.started.single()
        assertEquals(
            WorkflowStartRequest(
                orgId = ORG_A,
                owner = aliceAuto.memberId,
                automationId = automation.id,
                workflowId = "triage",
                inputs = mapOf("ticket" to "T-2026-10-30"),
                repository = "acme/api",
                idempotencyKey = "automation-run:${run.id}",
            ),
            request,
            "The empty input is left out, so the workflow's default applies",
        )
        assertEquals(emptyList(), launcher.launched, "No session is created")
        assertEquals("wfr_1", repository.runs.getValue(run.id).workflowRunId)
    }

    @Test
    fun aRefusedOrFailingStartFailsTheRunWithAStableCodeAndACancellationIsNeverSwallowed() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition()).automation
        workflows.next = { WorkflowStart.Refused("run_limit") }
        val limited = runs.test(aliceAuto, automation.id, null, "t1").run
        assertEquals(RunStatus.FAILED, limited.status)
        assertEquals("run_limit", limited.reason)
        workflows.next = { error("T-2026-10-30 is secret") }
        val failed = runs.test(aliceAuto, automation.id, null, "t2").run
        assertEquals("start_failed:IllegalStateException", failed.reason)
        workflows.next = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { runs.test(aliceAuto, automation.id, null, "t3") }
    }

    @Test
    fun aTriggerWhileTheWorkflowRunStillRunsIsSkippedForOverlap() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition()).automation
        val first = runs.test(aliceAuto, automation.id, null, "t1").run
        workflows.states["wfr_1"] = WorkflowRunState.RUNNING
        assertEquals(RunStatus.SKIPPED_OVERLAP, runs.test(aliceAuto, automation.id, null, "t2").run.status)
        workflows.states["wfr_1"] = WorkflowRunState.SUCCEEDED
        assertEquals(RunStatus.STARTED, runs.test(aliceAuto, automation.id, null, "t3").run.status)
        assertEquals(RunStatus.STARTED, repository.runs.getValue(first.id).status)
        val allowed = manage.create(aliceAuto, workflowDefinition().copy(name = "Again", overlap = Overlap.ALLOW))
        runs.test(aliceAuto, allowed.automation.id, null, "t4")
        workflows.states["wfr_3"] = WorkflowRunState.RUNNING
        assertEquals(RunStatus.STARTED, runs.test(aliceAuto, allowed.automation.id, null, "t5").run.status)
    }

    @Test
    fun theSchedulerEndsEachRunAsItsWorkflowRunEnds() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition().copy(overlap = Overlap.ALLOW)).automation
        val started = (1..5).map { runs.test(aliceAuto, automation.id, null, "t$it").run }
        workflows.states += mapOf(
            "wfr_1" to WorkflowRunState.RUNNING,
            "wfr_2" to WorkflowRunState.SUCCEEDED,
            "wfr_3" to WorkflowRunState.FAILED,
            "wfr_4" to WorkflowRunState.CANCELLED,
        )
        runs.tick()
        val ended = started.map { repository.runs.getValue(it.id).let { run -> run.status to run.reason } }
        assertEquals(
            listOf(
                RunStatus.STARTED to null,
                RunStatus.SUCCEEDED to null,
                RunStatus.FAILED to "workflow_failed",
                RunStatus.CANCELLED to "workflow_cancelled",
                RunStatus.FAILED to "workflow_missing",
            ),
            ended,
        )
        assertEquals(clock.now, repository.runs.getValue(started[1].id).updatedAt)
    }

    @Test
    fun cancellingAStartedRunCancelsItsWorkflowRunAsTheOwnerWhoeverCancels() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition()).automation
        val run = runs.test(aliceAuto, automation.id, null, "t1").run
        val cancelled = runs.cancel(daveAuto, automation.id, run.id)
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals(listOf("${aliceAuto.memberId}:wfr_1"), workflows.cancelled)
        val other = runs.test(aliceAuto, automation.id, null, "t2").run
        workflows.cancels = false
        assertFailsWith<AutomationRunEndedException> { runs.cancel(aliceAuto, automation.id, other.id) }
        assertEquals(RunStatus.STARTED, repository.runs.getValue(other.id).status)
    }

    @Test
    fun aRunCancelledWhileItsWorkflowRunStartsLeavesNoWorkflowRunGoing() = runTest {
        val automation = manage.create(aliceAuto, workflowDefinition()).automation
        workflows.next = { request ->
            val id = Uuid.parse(request.idempotencyKey.removePrefix("automation-run:"))
            repository.runs[id] = repository.runs.getValue(id).copy(status = RunStatus.CANCELLED)
            WorkflowStart.Started("late")
        }
        val raced = runs.test(aliceAuto, automation.id, null, "t1").run
        assertEquals(RunStatus.CANCELLED, raced.status)
        assertEquals(listOf("${aliceAuto.memberId}:late"), workflows.cancelled)
    }

    @Test
    fun aDryRunShowsTheRenderedInputsAndAnEmptyPrompt() = runTest {
        val hook = AutomationDefinition(
            name = "Ticket",
            trigger = AutomationTrigger(TriggerKind.WEBHOOK),
            action = AutomationAction(
                "",
                workflowId = "triage",
                inputs = mapOf("ticket" to "{{t}}", "label" to "{{p}}"),
            ),
            variables = listOf(AutomationVariable("t", "$.t"), AutomationVariable("p", "$.p", required = false)),
        )
        val automation = manage.create(aliceAuto, hook).automation
        val dry = runs.dryRun(aliceAuto, automation.id, buildJsonObject { put("t", "T-9") })
        assertEquals("", dry.renderedPrompt)
        assertEquals(mapOf("ticket" to "T-9"), dry.renderedInputs)
        assertEquals(mapOf("ticket" to "T-9"), dry.toResponse().renderedInputs)
        val session = manage.create(aliceAuto, hook.copy(name = "Plain", action = AutomationAction("Fix {{t}}")))
        val plain = runs.dryRun(aliceAuto, session.automation.id, buildJsonObject { put("t", "1") })
        assertNull(plain.toResponse().renderedInputs)
    }

    @Test
    fun theRequestCarriesTheWorkflowActionAndTheRunAnswersItsWorkflowRun() = runTest {
        val request = AutomationRequest(
            name = "Triage",
            trigger = AutomationTrigger(TriggerKind.CRON, cron = "0 21 * * *", timezone = "Europe/Paris"),
            action = AutomationActionRequest(
                workflowId = "triage",
                inputs = mapOf("ticket" to "T"),
                repository = "a/b",
            ),
        )
        val action = request.toDefinition().action
        val expected = AutomationAction("", workflowId = "triage", inputs = mapOf("ticket" to "T"), repository = "a/b")
        assertEquals(expected, action)
        val automation = manage.create(aliceAuto, request.toDefinition()).automation
        val run = runs.test(aliceAuto, automation.id, null, "t1").run
        assertEquals("wfr_1", run.toResponse().workflowRunId)
    }

    @Test
    fun aWorkflowAnAutomationStartsIsNamedByTheUsageTheCompilerAsks() = runTest {
        val usage = WorkflowsStartedByAutomations(repository)
        manage.create(aliceAuto, workflowDefinition())
        assertEquals("Triage", usage.automationStarting(ORG_A, "triage"))
        assertNull(usage.automationStarting(ORG_A, "other"))
        assertNull(usage.automationStarting(ORG_B, "triage"))
    }
}
