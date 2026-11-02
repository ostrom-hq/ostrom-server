// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase

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
 */
data class WorkflowRunsRoutesDependencies(
    val actors: GateActorResolver,
    val reportCheck: ReportCheckUseCase,
    val listChecks: ListChecksUseCase,
    val sendNotification: SendNotificationUseCase,
    val listNotifications: ListNotificationsUseCase,
    val signWorkflowReceipt: SignWorkflowReceiptUseCase,
    val bundle: GetReceiptBundleUseCase,
)
