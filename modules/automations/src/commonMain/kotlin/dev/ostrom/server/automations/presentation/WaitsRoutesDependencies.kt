// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AnswerWaitUseCase
import dev.ostrom.server.automations.domain.AskWaitUseCase
import dev.ostrom.server.automations.domain.ReadWaitsUseCase
import dev.ostrom.server.automations.domain.WaitAgentResolver
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the wait routes need.
 *
 * @property ask Asks.
 * @property read Reads.
 * @property answer Answers and cancels.
 * @property orgResolver The member a request acts for.
 * @property agents The member an agent token asks for, on the waits MCP server only.
 */
data class WaitsRoutesDependencies(
    val ask: AskWaitUseCase,
    val read: ReadWaitsUseCase,
    val answer: AnswerWaitUseCase,
    val orgResolver: PrincipalOrgResolver,
    val agents: WaitAgentResolver,
)
