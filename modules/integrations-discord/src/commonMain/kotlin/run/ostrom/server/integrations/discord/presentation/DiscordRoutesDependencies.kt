// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.presentation

import run.ostrom.server.integrations.discord.domain.CompleteDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.GetDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.ReceiveDiscordInteractionUseCase
import run.ostrom.server.integrations.discord.domain.StartDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.UnlinkDiscordUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the Discord routes need.
 *
 * @property orgs Resolves the caller's membership.
 * @property get Reads the caller's link.
 * @property start Starts linking the caller.
 * @property complete Finishes a link at Discord's return.
 * @property unlink Removes the caller's link.
 * @property interactions Receives Discord's interactions.
 * @property consoleUrl The console's origin, where the callback sends the browser back.
 */
data class DiscordRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val get: GetDiscordLinkUseCase,
    val start: StartDiscordLinkUseCase,
    val complete: CompleteDiscordLinkUseCase,
    val unlink: UnlinkDiscordUseCase,
    val interactions: ReceiveDiscordInteractionUseCase,
    val consoleUrl: String,
)
