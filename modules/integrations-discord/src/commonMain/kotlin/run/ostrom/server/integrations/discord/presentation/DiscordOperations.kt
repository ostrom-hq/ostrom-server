// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.presentation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.DiscordLink
import run.ostrom.core.api.models.DiscordLinkStart
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.queryText

/** The notifications module declares this tag; the Discord operations belong to it. */
private const val TAG = "notifications"

private const val INTERACTIVE = "A person's interactive login only (a browser session, the unlocked app or " +
    "`ostrom login`); a service key, an agent or a device token is 403."

private const val ORG_RESOLUTION = "404 and 409 are the organization's resolution (`Ostrom-Org`)."

private const val NOT_A_PERSON = "Not a person's interactive login (`forbidden`)."

private const val NO_ORG = "The caller is no member of an organization the request can act in (`not_found`)."

/**
 * The Discord integration's operations: the caller's own link to a Discord account, Discord's return from a link, and
 * the interactions Discord sends to the instance's application. The link is the contract's `DiscordLink`; Discord's
 * interaction is a signed JSON object without a schema.
 */
internal fun ApiCatalog.discordOperations() {
    interactionOperations()
    linkReadOperations()
    linkWriteOperations()
    callbackOperations()
}

/** Discord's interactions. */
private fun ApiCatalog.interactionOperations() {
    post("/webhooks/discord") {
        operationId = "receiveDiscordInteraction"
        tag(TAG)
        summary = "Discord interactions (the Reject button of a yes request, and the PING Discord sends when the URL " +
            "is saved)."
        description = "Called by Discord only, authenticated by `X-Signature-Ed25519` (Ed25519 of " +
            "`X-Signature-Timestamp` followed by the raw body, with the application's public key) with a " +
            "timestamp at most 5 minutes old. The interactions endpoint URL of the instance's Discord " +
            "application (`discord.v1`), checked with the public key of the `discord` settings section. A " +
            "body of more than 64 KiB is refused (400) without being read or verified; then a bad signature, " +
            "a timestamp more than 5 minutes away, or Discord not configured is 401, before the interaction " +
            "is parsed. A PING (type 1) answers `{\"type\":1}`. Reject answers no for the approver whose " +
            "Discord account clicked it (the member who linked that `discordUserId` in the request's " +
            "organization, answer channel `discord`), and the response itself updates the message (type 7) " +
            "with the outcome; anyone else's click answers a message only they see (type 4) and changes " +
            "nothing. Approve is a link to the console's page of the request (`/yes/{approvalId}`): a yes " +
            "always needs a fresh proof of who the approver is, which a Discord click is not, so no yes is " +
            "ever given here. Once a request is decided on any channel, the approvers' messages lose their " +
            "buttons."
        parameters {
            headerText(
                "X-Signature-Ed25519",
                "The signature of the request: Ed25519 of `X-Signature-Timestamp` followed by the raw body, in " +
                    "hexadecimal, with the Discord application's public key.",
                required = true,
            )
            headerText(
                "X-Signature-Timestamp",
                "When Discord sent the request, in Unix seconds, at most 5 minutes from the server's clock.",
                required = true,
            )
        }
        responses {
            ok<JsonObject>(
                "The interaction response, a JSON object: `type` 1 (PONG), 4 (a message for the clicker only) " +
                    "or 7 (the updated message).",
            )
            badRequest("A body of more than 64 KiB, or an interaction this server does not answer " +
                "(`invalid_request`).")
            error(HttpStatusCode.Unauthorized, "A missing or bad signature, a timestamp more than 5 minutes away, " +
                "or Discord not configured (`unauthorized`).")
        }
    }
}

/** Reading the caller's own link to a Discord account. */
private fun ApiCatalog.linkReadOperations() {
    get("/api/v1/discord/link") {
        operationId = "getDiscordLink"
        tag(TAG)
        summary = "The caller's own Discord link in the organization."
        description = "`discord.v1`: the Discord account the caller's notifications and yes requests go to as " +
            "direct messages. $INTERACTIVE Works while Discord is not configured (`configured` false), so a " +
            "member can still see and remove a link. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<DiscordLink>("Whether Discord is configured, whether the caller is linked, and to which Discord " +
                "account and since when.")
            error(HttpStatusCode.Forbidden, NOT_A_PERSON)
            notFound(NO_ORG)
        }
    }
}

/** Starting and removing the caller's own link. */
private fun ApiCatalog.linkWriteOperations() {
    post("/api/v1/discord/link") {
        operationId = "linkDiscord"
        tag(TAG)
        summary = "Start linking the caller's Discord account: the URL of Discord's authorization to open in the " +
            "same browser."
        description = "A browser session only (the console), 403 otherwise: Discord proves which account the " +
            "person holds by signing them in (OAuth2, scope `identify`), and the attempt is bound to the " +
            "browser session that started it. The answer carries Discord's authorization URL with a " +
            "single-use `state` whose hash is stored with the caller's organization and member for 10 " +
            "minutes; Discord then returns to `discordLinkCallback`, which must be registered as a redirect " +
            "of the instance's Discord application. Nobody types a Discord id, so nobody links another " +
            "person's account. Starting again replaces an unfinished attempt; an existing link stays until " +
            "the new one is stored. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<DiscordLinkStart>("Where to send the browser.")
            error(HttpStatusCode.Forbidden, "Not a browser session of a person (`forbidden`).")
            notFound(NO_ORG)
            conflict("Discord is not configured: the `discord` settings section misses the bot token, the " +
                "application id, the client secret or the server id (`discord_not_configured`).")
        }
    }
    delete("/api/v1/discord/link") {
        operationId = "unlinkDiscord"
        tag(TAG)
        summary = "Remove the caller's own Discord link."
        description = "$INTERACTIVE 204 also when not linked. Nothing more is sent to that Discord account, and " +
            "a Reject from a message sent before changes nothing. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            empty("Unlinked, or nothing to unlink.")
            error(HttpStatusCode.Forbidden, NOT_A_PERSON)
            notFound(NO_ORG)
        }
    }
}

/** Discord's return from a link. */
private fun ApiCatalog.callbackOperations() {
    get("/api/v1/discord/link/callback") {
        operationId = "discordLinkCallback"
        tag(TAG)
        summary = "Discord's return from a link, a redirect of the instance's Discord application."
        description = "The single-use state identifies the attempt and its member, and must come back with the " +
            "browser session that started it: a link opened in another browser binds nothing " +
            "(`discord_link_browser_mismatch`). The code is exchanged for the Discord account (`identify`), " +
            "which must be a member of the instance's Discord server, since a bot only writes to a person " +
            "it shares a server with. Once stored, the account is told by a direct message that it now " +
            "receives this member's notifications (best effort). The browser is always sent back to the " +
            "console's `/me/notifications` page with `discord=linked` or `discord=<error.type>` " +
            "(`discord_link_not_found`, `discord_link_browser_mismatch`, `discord_link_denied`, " +
            "`discord_user_taken`, `discord_not_in_guild`, `discord_not_configured`, `discord_unreachable`; " +
            "a missing or unknown state is `discord_link_not_found`, whatever else comes with it): this " +
            "route never answers with an error body."
        parameters {
            queryText("code", "The authorization code Discord returns.")
            queryText("state", "The single-use state of the attempt, as `linkDiscord` sent it to Discord.")
            queryText("error", "Discord's error, when the person declined.")
        }
        responses {
            empty("A redirect to the console's notifications page, with the outcome in `discord`.",
                HttpStatusCode.Found)
        }
    }
}
