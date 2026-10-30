// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import dev.ostrom.server.runners.domain.OpenTerminalUseCase
import dev.ostrom.server.runners.domain.TerminalActivity
import dev.ostrom.server.runners.domain.TerminalTickets
import dev.ostrom.server.runners.infrastructure.TerminalRelay

/**
 * What the terminal routes need.
 *
 * @property relay The terminal relay.
 * @property openTerminal Checks a member may attach.
 * @property issueTicket Issues browser tickets.
 * @property tickets Redeems them.
 * @property authenticateRunner Checks a runner's token.
 * @property authenticator Resolves members' bearer tokens (the CLI sends one on the upgrade).
 * @property activity Records terminal input as session activity.
 * @property orgResolver The member a bearer token acts for.
 */
data class TerminalRoutesDependencies(
    val relay: TerminalRelay,
    val openTerminal: OpenTerminalUseCase,
    val issueTicket: IssueTerminalTicketUseCase,
    val tickets: TerminalTickets,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val authenticator: TokenAuthenticator,
    val activity: TerminalActivity,
    val orgResolver: PrincipalOrgResolver,
)
