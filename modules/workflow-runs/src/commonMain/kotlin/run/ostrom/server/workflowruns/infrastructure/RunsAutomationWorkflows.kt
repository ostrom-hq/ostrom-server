// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.automations.domain.AutomationWorkflows
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.domain.WorkflowRunState
import run.ostrom.server.automations.domain.WorkflowStart
import run.ostrom.server.automations.domain.WorkflowStartRequest
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunEndedException
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.MemberStart
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import kotlin.uuid.Uuid

/**
 * [AutomationWorkflows] on the workflow runs (TC-AUT-5): an automation's workflow action starts its run through
 * [StartWorkflowRunUseCase] as the automation's owner (their login is the run's starter), under the run's
 * idempotency key, and cancels it as that starter.
 *
 * @param workflows The organization's workflows.
 * @param start Starts a run.
 * @param cancel Cancels a run.
 * @param runs The runs.
 * @param logins The members' logins.
 */
class RunsAutomationWorkflows(
    private val workflows: RunWorkflows,
    private val start: StartWorkflowRunUseCase,
    private val cancel: CancelWorkflowRunUseCase,
    private val runs: StartedRunsRepository,
    private val logins: MemberLogins,
) : AutomationWorkflows {
    override suspend fun inputs(orgId: Uuid, workflowId: String): List<WorkflowInputSpec>? {
        val workflow = workflows.find(orgId, workflowId)?.takeIf { it.status in RUNNABLE } ?: return null
        return workflow.inputs.map { WorkflowInputSpec(it.id, it.required, !it.default.isNullOrBlank()) }
    }

    override suspend fun start(request: WorkflowStartRequest): WorkflowStart {
        val starter = loginOf(request.orgId, request.owner) ?: return WorkflowStart.Refused(OWNER_UNAVAILABLE)
        val member = MemberStart(
            orgId = request.orgId,
            starter = starter,
            workflowId = request.workflowId,
            inputs = request.inputs,
            repository = request.repository,
            key = request.idempotencyKey,
            source = AUTOMATION,
            automationId = request.automationId.toString(),
        )
        // A refusal is a stable code; never its message, which may quote an input.
        return try {
            WorkflowStart.Started(start.startFor(member).run.id)
        } catch (expected: RunWorkflowNotFoundException) {
            WorkflowStart.Refused(WORKFLOW_MISSING)
        } catch (expected: WorkflowNotAcceptedException) {
            WorkflowStart.Refused(WORKFLOW_MISSING)
        } catch (expected: InvalidRunException) {
            WorkflowStart.Refused("invalid_inputs")
        } catch (refused: RunLimitException) {
            WorkflowStart.Refused(refused.type)
        }
    }

    override suspend fun state(orgId: Uuid, workflowRunId: String): WorkflowRunState? =
        runs.find(orgId, workflowRunId)?.let {
            when (it.status) {
                RunStatus.SUCCEEDED -> WorkflowRunState.SUCCEEDED
                RunStatus.FAILED -> WorkflowRunState.FAILED
                RunStatus.CANCELLED -> WorkflowRunState.CANCELLED
                else -> WorkflowRunState.RUNNING
            }
        }

    override suspend fun cancel(orgId: Uuid, owner: String, workflowRunId: String): Boolean {
        val starter = loginOf(orgId, owner) ?: return false
        return try {
            cancel.cancelAsStarter(orgId, starter, workflowRunId)
            true
        } catch (expected: RunNotFoundException) {
            false
        } catch (expected: RunEndedException) {
            false
        }
    }

    // The login of a member, or null when they are no longer one (or the id is not a member id).
    private suspend fun loginOf(orgId: Uuid, memberId: String): String? {
        val id = Uuid.parseOrNull(memberId) ?: return null
        return logins.loginsOf(orgId, listOf(id))[id]
    }
}

// The workflows a run may start, the source of its runs, and the codes of its refusals. Top-level, not in a private
// companion, whose lines Kover counts as partly missed.
private val RUNNABLE = setOf(WorkflowStatus.ACCEPTED, WorkflowStatus.EDITED)
private const val AUTOMATION = "automation"
private const val WORKFLOW_MISSING = "workflow_missing"
private const val OWNER_UNAVAILABLE = "owner_unavailable"
