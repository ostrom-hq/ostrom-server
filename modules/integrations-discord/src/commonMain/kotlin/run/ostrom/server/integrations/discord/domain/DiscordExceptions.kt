// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import run.ostrom.core.api.models.DiscordChannels
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A credential that is not a person's interactive login: 403. A service key has no Discord account. */
class DiscordLinkForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "A Discord link is a person's own: sign in with a browser, the app or `ostrom login`.",
)

/** The `discord` settings section is incomplete: 409 `discord_not_configured`. */
class DiscordNotConfiguredException : OstromException(
    ErrorKind.CONFLICT,
    "Discord is not configured on this instance: an administrator saves its application first.",
    DiscordChannels.NOT_CONFIGURED,
)

/** Another member of the organization linked that Discord account: `discord_user_taken`. */
class DiscordUserTakenException : OstromException(
    ErrorKind.CONFLICT,
    "Another member of this organization linked that Discord account.",
    DiscordChannels.USER_TAKEN,
)

/** The account is not in the instance's Discord server: `discord_not_in_guild`. */
class DiscordNotInGuildException : OstromException(
    ErrorKind.CONFLICT,
    "Join the instance's Discord server first: its bot only writes to its members.",
    DiscordChannels.NOT_IN_GUILD,
)

/** No attempt has that state (unknown, used, expired), or the member left: `discord_link_not_found`. */
class DiscordLinkNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "This Discord link has expired or was already used: start again from your notifications.",
    DiscordChannels.LINK_NOT_FOUND,
)

/** The state came back in another browser session: `discord_link_browser_mismatch`. */
class DiscordLinkBrowserMismatchException : OstromException(
    ErrorKind.FORBIDDEN,
    "Finish the Discord link in the browser that started it.",
    DiscordChannels.LINK_BROWSER_MISMATCH,
)

/** The person declined, or Discord refused the code: `discord_link_denied`. */
class DiscordLinkDeniedException : OstromException(
    ErrorKind.FORBIDDEN,
    "Discord did not authorize the link.",
    DiscordChannels.LINK_DENIED,
)

/** Discord did not answer: `discord_unreachable`. */
class DiscordUnreachableException : OstromException(
    ErrorKind.UNAVAILABLE,
    "Discord does not answer; try again later.",
    DiscordChannels.UNREACHABLE,
)
