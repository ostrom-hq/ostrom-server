// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.domain.WorkflowRunState
import run.ostrom.server.automations.domain.WorkflowStart
import run.ostrom.server.automations.domain.WorkflowStartRequest
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunBusyException
import run.ostrom.server.workflowruns.domain.models.RunEndedException
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunWorkflow
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.MemberStart
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.StartedRun
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.infrastructure.RunsAutomationWorkflows
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunsAutomationWorkflowsTest {
    private val alice = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
    private val gone = Uuid.parse("00000000-0000-0000-0000-000000000900")

    private val workflows = object : RunWorkflows {
        val known = listOf(
            RunWorkflow(
                "ship",
                "Ship",
                2,
                WorkflowStatus.ACCEPTED,
                listOf(
                    WorkflowInput("branch", "Branch", required = true),
                    WorkflowInput("note", "Note", required = true, default = "none"),
                    WorkflowInput("blank", "Blank", default = " "),
                ),
            ),
            RunWorkflow("idea", "Idea", 1, WorkflowStatus.PROPOSED, emptyList()),
        ).associateBy { it.id }

        override suspend fun find(orgId: Uuid, id: String): RunWorkflow? = known[id]
    }

    private class Starts : StartWorkflowRunUseCase {
        val starts = mutableListOf<MemberStart>()
        var next: (MemberStart) -> StartedRun = { StartedRun(run("wfr_1", it.starter), replayed = false) }

        override suspend fun invoke(
            actor: GateActor,
            workflowId: String,
            inputs: Map<String, String>,
            person: Uuid?,
        ): WorkflowRun = error("An automation starts as a member.")

        override suspend fun startFor(start: MemberStart): StartedRun {
            starts += start
            return next(start)
        }
    }

    private class Cancels : CancelWorkflowRunUseCase {
        val cancelled = mutableListOf<String>()
        var next: () -> Unit = {}

        override suspend fun invoke(actor: GateActor, id: String, person: Uuid?): WorkflowRun =
            error("An automation cancels as the starter.")

        override suspend fun cancelAsStarter(orgId: Uuid, starter: String, id: String): WorkflowRun {
            next()
            cancelled += "$starter:$id"
            return run(id, starter)
        }
    }

    private val runs = object : StartedRunsRepository {
        val stored = listOf(
            run("wfr_q", "alice", RunStatus.QUEUED),
            run("wfr_r", "alice", RunStatus.RUNNING),
            run("wfr_w", "alice", RunStatus.WAITING),
            run("wfr_s", "alice", RunStatus.SUCCEEDED),
            run("wfr_f", "alice", RunStatus.FAILED),
            run("wfr_c", "alice", RunStatus.CANCELLED),
        ).associateBy { it.id }

        override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) =
            error("Not stored here.")

        override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit) =
            error("Not stored here.")

        override suspend fun find(orgId: Uuid, id: String): WorkflowRun? = stored[id]

        override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> = error("Not listed here.")
    }

    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = error("Unused.")

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            memberIds.filter { it == alice }.associateWith { "alice" }
    }

    private val starts = Starts()
    private val cancels = Cancels()
    private val adapter = RunsAutomationWorkflows(workflows, starts, cancels, runs, logins)

    private fun request(owner: String = alice.toString()) = WorkflowStartRequest(
        orgId = ORG,
        owner = owner,
        automationId = Uuid.parse("00000000-0000-0000-0000-0000000a0701"),
        workflowId = "ship",
        inputs = mapOf("branch" to "main"),
        repository = "acme/api",
        idempotencyKey = "automation-run:r1",
    )

    @Test
    fun theInputsOfAWorkflowARunMayStartAreGivenAndNoneForAProposalOrAnUnknownOne() = runTest {
        assertEquals(
            listOf(
                WorkflowInputSpec("branch", required = true, hasDefault = false),
                WorkflowInputSpec("note", required = true, hasDefault = true),
                WorkflowInputSpec("blank", required = false, hasDefault = false),
            ),
            adapter.inputs(ORG, "ship"),
        )
        assertNull(adapter.inputs(ORG, "idea"))
        assertNull(adapter.inputs(ORG, "absent"))
    }

    @Test
    fun aStartIsTheOwnersUnderTheRunsKeyAndARefusalIsAStableCode() = runTest {
        assertEquals(WorkflowStart.Started("wfr_1"), adapter.start(request()))
        assertEquals(
            MemberStart(
                orgId = ORG,
                starter = "alice",
                workflowId = "ship",
                inputs = mapOf("branch" to "main"),
                repository = "acme/api",
                key = "automation-run:r1",
                source = "automation",
                automationId = "00000000-0000-0000-0000-0000000a0701",
            ),
            starts.starts.single(),
        )
        val refusals = listOf(
            RunWorkflowNotFoundException() to "workflow_missing",
            WorkflowNotAcceptedException() to "workflow_missing",
            InvalidRunException("inputs.branch: secret value") to "invalid_inputs",
            RunLimitException(20) to "run_limit",
        )
        for ((error, code) in refusals) {
            starts.next = { throw error }
            assertEquals(WorkflowStart.Refused(code), adapter.start(request()))
        }
        starts.next = { throw RunBusyException() }
        assertFailsWith<RunBusyException> { adapter.start(request()) }
        assertEquals(WorkflowStart.Refused("owner_unavailable"), adapter.start(request(gone.toString())))
        assertEquals(WorkflowStart.Refused("owner_unavailable"), adapter.start(request("not-a-member-id")))
    }

    @Test
    fun aWorkflowRunsStateIsRunningUntilItEnds() = runTest {
        val states = listOf("wfr_q", "wfr_r", "wfr_w", "wfr_s", "wfr_f", "wfr_c").map { adapter.state(ORG, it) }
        assertEquals(
            listOf(
                WorkflowRunState.RUNNING,
                WorkflowRunState.RUNNING,
                WorkflowRunState.RUNNING,
                WorkflowRunState.SUCCEEDED,
                WorkflowRunState.FAILED,
                WorkflowRunState.CANCELLED,
            ),
            states,
        )
        assertNull(adapter.state(ORG, "wfr_none"))
    }

    @Test
    fun aCancelIsTheStartersAndAGoneOrEndedRunHasNothingToCancel() = runTest {
        assertTrue(adapter.cancel(ORG, alice.toString(), "wfr_r"))
        assertEquals(listOf("alice:wfr_r"), cancels.cancelled)
        assertFalse(adapter.cancel(ORG, gone.toString(), "wfr_r"))
        cancels.next = { throw RunNotFoundException() }
        assertFalse(adapter.cancel(ORG, alice.toString(), "wfr_x"))
        cancels.next = { throw RunEndedException() }
        assertFalse(adapter.cancel(ORG, alice.toString(), "wfr_s"))
        cancels.next = { throw RunBusyException() }
        assertFailsWith<RunBusyException> { adapter.cancel(ORG, alice.toString(), "wfr_r") }
    }
}

private fun run(id: String, starter: String, status: RunStatus = RunStatus.QUEUED) = WorkflowRun(
    id = id,
    workflowId = "ship",
    workflowName = "Ship",
    workflowVersion = 2,
    status = status,
    startedBy = starter,
    source = "automation",
    startedAt = "2026-11-13T10:00:00.000Z",
)
