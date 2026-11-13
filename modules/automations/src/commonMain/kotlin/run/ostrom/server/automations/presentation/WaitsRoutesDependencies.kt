// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.ReadWaitsUseCase
import run.ostrom.server.automations.domain.ReceiveWaitEventUseCase
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WakeWaitsUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the wait routes need.
 *
 * @property ask Asks.
 * @property read Reads.
 * @property answer Answers and cancels.
 * @property orgResolver The member a request acts for.
 * @property agents The member an agent token asks for, on the waits MCP server and the wake only.
 * @property wake Wakes an agent's session with the answers to its waits.
 * @property event Resolves a wait for an event with what its caller sent.
 * @property publicUrl The server's public URL, without a trailing slash, for the event addresses; empty for relative
 *   addresses.
 */
data class WaitsRoutesDependencies(
    val ask: AskWaitUseCase,
    val read: ReadWaitsUseCase,
    val answer: AnswerWaitUseCase,
    val orgResolver: PrincipalOrgResolver,
    val agents: WaitAgentResolver,
    val wake: WakeWaitsUseCase,
    val event: ReceiveWaitEventUseCase,
    val publicUrl: String,
)
