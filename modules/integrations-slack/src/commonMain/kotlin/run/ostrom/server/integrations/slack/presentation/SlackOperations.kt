// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.presentation

import run.ostrom.core.api.models.SlackLink
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import io.ktor.http.HttpStatusCode

/** The notifications module declares this tag; the Slack operations belong to it. */
private const val TAG = "notifications"

private const val INTERACTIVE = "A person's interactive login only (a browser session, the unlocked app or " +
    "`ostrom login`); a service key, an agent or a device token is 403."

private const val ORG_RESOLUTION = "404 and 409 are the organization's resolution (`Ostrom-Org`)."

private const val NOT_A_PERSON = "Not a person's interactive login (`forbidden`)."

private const val NO_ORG = "The caller is no member of an organization the request can act in (`not_found`)."

/**
 * The Slack integration's operations: the caller's own link to a Slack account, and the interactions Slack sends to
 * the instance's app. The link is the contract's `SlackLink`; Slack's interaction is a signed form without a schema.
 */
internal fun ApiCatalog.slackOperations() {
    interactionOperations()
    linkReadOperations()
    linkWriteOperations()
}

/** Slack's interactions. */
private fun ApiCatalog.interactionOperations() {
    post("/webhooks/slack") {
        operationId = "receiveSlackInteraction"
        tag(TAG)
        summary = "Slack interactions (the Approve and Reject buttons of a yes request)."
        description = "Called by Slack only, authenticated by `X-Slack-Signature` (HMAC-SHA256 of " +
            "`v0:<timestamp>:<body>`) with `X-Slack-Request-Timestamp` at most 5 minutes old. The request URL " +
            "of the instance's Slack app (`slack.v1`), checked with the signing secret of the `slack` " +
            "settings section: a bad signature, a timestamp more than 5 minutes away, or Slack not " +
            "configured is 401, before the payload is read. The body is the form Slack sends, with the " +
            "interaction as JSON in its `payload` field, at most 64 KiB: a larger one is refused (400) " +
            "without being read, and a signed one without a `payload` is 400. Reject answers no for the " +
            "approver whose Slack account clicked it (the member who linked that `slackUserId` in the " +
            "request's organization, answer channel `slack`), and the message is updated with the outcome; " +
            "anyone else's click changes nothing. Approve is a link to the console's page of the request " +
            "(`/yes/{approvalId}`): a yes always needs a fresh proof of who the approver is, which a Slack " +
            "click is not, so no yes is ever given here; the interaction Slack still sends for that link is " +
            "acknowledged and changes nothing. Once a request is decided on any channel, the approvers' " +
            "messages lose their buttons. 200 with an empty body for every signed interaction, handled or " +
            "ignored (Slack retries anything else)."
        parameters {
            headerText(
                "X-Slack-Signature",
                "The signature of the request: `v0=` and the HMAC-SHA256 of `v0:<timestamp>:<body>` with the " +
                    "Slack app's signing secret.",
                required = true,
            )
            headerText(
                "X-Slack-Request-Timestamp",
                "When Slack sent the request, in Unix seconds, at most 5 minutes from the server's clock.",
                required = true,
            )
        }
        responses {
            empty("Handled or ignored; the message in Slack is updated when a Reject was handled.",
                HttpStatusCode.OK)
            badRequest("A body of more than 64 KiB, or a signed one without a `payload` (`invalid_request`).")
            error(HttpStatusCode.Unauthorized, "A bad signature, a timestamp more than 5 minutes away, or Slack " +
                "not configured (`unauthorized`).")
        }
    }
}

/** Reading the caller's own link to a Slack account. */
private fun ApiCatalog.linkReadOperations() {
    get("/api/v1/slack/link") {
        operationId = "getSlackLink"
        tag(TAG)
        summary = "The caller's own Slack link in the organization."
        description = "`slack.v1`: the Slack account the caller's notifications and yes requests go to as " +
            "direct messages. $INTERACTIVE Works while Slack is not configured (`configured` false), so a " +
            "member can still see and remove a link. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<SlackLink>("Whether Slack is configured, whether the caller is linked, and to which Slack " +
                "account and since when.")
            error(HttpStatusCode.Forbidden, NOT_A_PERSON)
            notFound(NO_ORG)
        }
    }
}

/** Making and removing the caller's own link. */
private fun ApiCatalog.linkWriteOperations() {
    post("/api/v1/slack/link") {
        operationId = "linkSlack"
        tag(TAG)
        summary = "Link the caller to the Slack account of their proven e-mail address."
        description = "$INTERACTIVE The instance's Slack app looks the caller's proven e-mail address up in its " +
            "workspace (`users.lookupByEmail`, which matches only an address Slack itself confirmed): the " +
            "caller never types a Slack id, so nobody links another person's account. The app's bot token " +
            "needs the scopes `chat:write`, `users:read` and `users:read.email`. 409 `slack_not_configured` " +
            "while the `slack` settings section has no bot token, or Slack refuses it; 409 " +
            "`slack_email_unproven` when the caller has no proven address; 404 `slack_user_not_found` when " +
            "the workspace has no active person with it (a bot or a deactivated account never matches); 409 " +
            "`slack_user_taken` when another member of the organization linked that Slack account (one " +
            "member per Slack account and organization, so a click in Slack names one member); 503 " +
            "`unavailable` when Slack does not answer. Linking again replaces the link. Once stored, the " +
            "Slack account is told by a direct message that it now receives this member's notifications; " +
            "that message is best effort and never undoes the link."
        parameters {
            organizationHeader()
        }
        responses {
            ok<SlackLink>("The link, now stored.")
            error(HttpStatusCode.Forbidden, NOT_A_PERSON)
            notFound("The caller is no member of an organization the request can act in (`not_found`), or " +
                "the workspace has no active person with their proven address (`slack_user_not_found`).")
            conflict("Slack is not configured (`slack_not_configured`), the caller has no proven address " +
                "(`slack_email_unproven`), or another member linked that Slack account " +
                "(`slack_user_taken`).")
            error(HttpStatusCode.ServiceUnavailable, "Slack does not answer (`unavailable`).")
        }
    }
    delete("/api/v1/slack/link") {
        operationId = "unlinkSlack"
        tag(TAG)
        summary = "Remove the caller's own Slack link."
        description = "$INTERACTIVE 204 also when not linked. Nothing more is sent to that Slack account, and " +
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
