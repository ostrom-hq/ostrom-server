// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.AskGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalViewUseCase
import run.ostrom.server.gates.domain.usecases.GetGateUseCase
import run.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import run.ostrom.server.gates.domain.usecases.ReportLocalDenialUseCase
import run.ostrom.server.gates.domain.usecases.UseGateUseCase

/**
 * What the gates routes need.
 *
 * @property actors Resolves callers.
 * @property ask Opens a gate for a hook.
 * @property gate Reads a gate.
 * @property use Reserves, consumes or releases a yes.
 * @property answer Answers an approval.
 * @property approvals Lists approvals.
 * @property approval Reads an approval.
 * @property queue Reads the caller's yes queue.
 * @property view Reads an approval as the console and the app show it.
 * @property receipts Reads the signed receipt an answer returns.
 * @property localDenial Audits a refusal the hook made on the workstation.
 */
data class GatesRoutesDependencies(
    val actors: GateActorResolver,
    val ask: AskGateUseCase,
    val gate: GetGateUseCase,
    val use: UseGateUseCase,
    val answer: AnswerGateUseCase,
    val approvals: ListApprovalsUseCase,
    val approval: GetApprovalUseCase,
    val queue: GetApprovalQueueUseCase,
    val view: GetApprovalViewUseCase,
    val receipts: GateReceiptSigner,
    val localDenial: ReportLocalDenialUseCase,
)
