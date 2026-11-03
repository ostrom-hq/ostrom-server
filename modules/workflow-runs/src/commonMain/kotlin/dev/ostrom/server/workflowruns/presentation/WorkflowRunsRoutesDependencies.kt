// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.ServiceKeyAudit
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase
import dev.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase

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
)
