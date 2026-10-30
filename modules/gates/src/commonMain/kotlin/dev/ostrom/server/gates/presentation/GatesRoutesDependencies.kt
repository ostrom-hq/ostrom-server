// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import dev.ostrom.server.gates.domain.usecases.GetGateUseCase
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import dev.ostrom.server.gates.domain.usecases.UseGateUseCase

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
 */
data class GatesRoutesDependencies(
    val actors: GateActorResolver,
    val ask: AskGateUseCase,
    val gate: GetGateUseCase,
    val use: UseGateUseCase,
    val answer: AnswerGateUseCase,
    val approvals: ListApprovalsUseCase,
    val approval: GetApprovalUseCase,
)
