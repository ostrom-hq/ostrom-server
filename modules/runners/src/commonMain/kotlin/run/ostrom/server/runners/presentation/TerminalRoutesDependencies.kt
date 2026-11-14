// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import run.ostrom.server.runners.domain.OpenTerminalUseCase
import run.ostrom.server.runners.domain.TerminalActivity
import run.ostrom.server.runners.domain.TerminalTickets
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalReplica

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
 * @property replica Where this replica stands among the others; null with one replica (every terminal served here).
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
    val replica: TerminalReplica? = null,
)
