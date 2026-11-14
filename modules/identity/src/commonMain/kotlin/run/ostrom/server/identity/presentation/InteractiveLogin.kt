// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.SessionRequiredException
import run.ostrom.server.identity.domain.isBrowserSession
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.server.application.ApplicationCall

/**
 * The principal, refused unless it is a person's interactive login: a browser session, or the CLI's own sign-in
 * (`ostrom login`, the token of a workstation), as TeamCode's `teamcode keys` and `teamcode auth sessions` ("an
 * interactive login is required", S2-52 as decided on 14/11/2026). A service key, an agent's token, a personal or app
 * token and the elevated token of `ostrom login --admin` are refused: only the person's own sign-in on a machine they
 * hold may mint a key or end a session.
 *
 * @return The principal.
 *
 * @throws SessionRequiredException Otherwise.
 */
fun ApplicationCall.requireInteractiveLogin(): AuthenticatedPrincipal {
    val principal = requirePrincipal()
    if (!principal.isBrowserSession && principal.workstation == null) throw SessionRequiredException()
    return principal
}
