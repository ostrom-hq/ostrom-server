// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import run.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCase
import run.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import run.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCase
import run.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import run.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase

/**
 * What the workflow-runs routes need.
 *
 * @property actors Resolves the caller.
 * @property reportCheck Records a check run.
 * @property listChecks Lists them.
 * @property sendNotification Sends a notification.
 * @property listNotifications Lists them.
 * @property signWorkflowReceipt Signs a session's workflow receipt.
 * @property bundle Gathers a receipt's bundle.
 * @property checkRunnerToken Checks a runner token before its call's body is read (S2-46).
 * @property authenticateRunner Reads the cloud session of a runner's current execution.
 * @property reportRunnerCheck Records a check run the runner reports.
 * @property serviceKeys Audits the calls of a service key, the CI's caller of `reportCheck` and `signWorkflowReceipt`.
 * @property startRun Starts a run of a workflow.
 * @property listRuns Lists the runs of workflows.
 * @property getRun Reads a run of a workflow.
 * @property cancelRun Cancels a run of a workflow.
 */
data class WorkflowRunsRoutesDependencies(
    val actors: GateActorResolver,
    val reportCheck: ReportCheckUseCase,
    val listChecks: ListChecksUseCase,
    val sendNotification: SendNotificationUseCase,
    val listNotifications: ListNotificationsUseCase,
    val signWorkflowReceipt: SignWorkflowReceiptUseCase,
    val bundle: GetReceiptBundleUseCase,
    val checkRunnerToken: CheckRunnerTokenUseCase,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val reportRunnerCheck: ReportRunnerCheckUseCase,
    val serviceKeys: ServiceKeyAudit,
    val startRun: StartWorkflowRunUseCase,
    val listRuns: ListWorkflowRunsUseCase,
    val getRun: GetWorkflowRunUseCase,
    val cancelRun: CancelWorkflowRunUseCase,
)
