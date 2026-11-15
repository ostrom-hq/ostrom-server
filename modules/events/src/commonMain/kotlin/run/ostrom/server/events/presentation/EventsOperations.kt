// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import run.ostrom.core.api.models.CreateWebhookRequest
import run.ostrom.core.api.models.CreatedWebhook
import run.ostrom.core.api.models.UpdateWebhookRequest
import run.ostrom.core.api.models.Webhook
import run.ostrom.core.api.models.WebhookDelivery
import run.ostrom.core.api.models.WebhookDeliveryList
import run.ostrom.core.api.models.WebhookList
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val EVENTS_TAG = "events"
private const val WEBHOOKS_TAG = "webhooks"

private const val HOST = "An address off the public internet (`webhook_host_not_allowed`), or a webhook " +
    "that breaks a rule of its shape (`invalid_webhook`)."

private const val NOT_FOUND = "No such webhook in the caller's organization (`not_found`)."

/** The events module's operations: the live event feed and the organization's outgoing webhooks. */
internal fun ApiCatalog.eventsOperations() {
    tag(EVENTS_TAG, "The organization's live event feed, as one server-sent event stream.")
    tag(WEBHOOKS_TAG, "Outgoing webhooks: the events of the feed sent to the caller's own services, signed.")
    feedOperations()
    webhookCollectionOperations()
    webhookOperations()
    webhookActionOperations()
}

/** The live event feed. */
private fun ApiCatalog.feedOperations() {
    get("/api/v1/events") {
        operationId = "streamEvents"
        tag(EVENTS_TAG)
        summary = "The organization's live event feed (SSE)."
        description = "One server-sent event stream (`events.v1`) for everything the caller may see happen, " +
            "instead of one connection per resource. Each frame is `id: <seq>`, `event: <type>`, `data: " +
            "<OrgEvent>`. `seq` is the organization's own sequence, gap-free and allocated in the transaction " +
            "of the change it reports, so an event exists exactly when its change committed and a reader never " +
            "skips one, whichever replica it reads from. The types are an open list: a client ignores a type it " +
            "does not know. The data holds identifiers and short labels, never a transcript, a prompt or a " +
            "secret. **Who sees what.** A member sees the events that belong to them (`memberId`); an owner or " +
            "an admin sees the whole organization's, events that belong to no member included. A service key " +
            "needs `cloud:read` (or `cloud:write`, `cloud:admin`) and sees its creator's events; the whole " +
            "organization's with `cloud:admin` or `team:read`. Anything else: 403 `forbidden`. **Resuming.** " +
            "Send `Last-Event-ID` (an `EventSource` does it by itself) or `?after=`; the header wins. Without " +
            "either the stream starts at the newest event; `after=0` starts at the oldest retained one. A " +
            "cursor ahead of the organization's events is 400 `invalid_cursor`. Events are kept 7 days: when " +
            "the cursor is older than the oldest kept event, `event: gap` with `{\"oldestSeq\": n}` comes " +
            "first, then the stream goes on from there. `?types=a,b` keeps only those types (400 " +
            "`invalid_event_type` for an unknown one, at most 64). Keep-alive comments every 15 s, `retry: " +
            "2000`; the credential is checked again every 15 s and the stream ends with `event: denied` once it " +
            "stopped being valid. It never ends otherwise: reconnect from the last `id`."
        parameters {
            organizationHeader()
            headerText("Last-Event-ID", "The `seq` of the last event handled; wins over `after`.")
            queryText(
                "after",
                "The `seq` of the last event handled (0 replays every retained event); a `Last-Event-ID` header " +
                    "wins over it.",
            )
            queryText("types", "Comma-separated event types to keep.")
        }
        responses {
            empty("The event stream (`text/event-stream`): `id`, `event` and `data` frames until the " +
                "credential stops being valid.", HttpStatusCode.OK)
            badRequest("A cursor ahead of the organization's events or not a sequence (`invalid_cursor`), " +
                "or an unknown or too long list of `types` (`invalid_event_type`).")
        }
    }
}

/** Listing and adding webhooks. */
private fun ApiCatalog.webhookCollectionOperations() {
    get("/api/v1/webhooks") {
        operationId = "listWebhooks"
        tag(WEBHOOKS_TAG)
        summary = "The organization's outgoing webhooks."
        description = "Outgoing webhooks (`webhooks.v1`) send the events of the organization's feed (`GET " +
            "/api/v1/events`) to the caller's own services as signed HTTP requests, retried until the receiver " +
            "answers. A webhook receives the whole organization's events, so only an owner or an admin reads " +
            "and manages them: with an interactive login (a browser session, the unlocked app or `ostrom " +
            "login`), or a service key of theirs holding `cloud:admin` (403 `forbidden` for anyone else: a " +
            "personal token made in the console whatever its `cloud:*` scopes, a member's key and a `team:read` " +
            "key included). At most 20 per organization, oldest first."
        parameters {
            organizationHeader()
        }
        responses {
            ok<WebhookList>("The organization's webhooks, oldest first.")
        }
    }
    post("/api/v1/webhooks") {
        operationId = "createWebhook"
        tag(WEBHOOKS_TAG)
        summary = "Add an outgoing webhook; its signing secret is returned once."
        description = "The target is an `https` URL of at most 2 048 characters, without credentials or " +
            "fragment, on the public internet: `localhost`, loopback, private (RFC 1918, unique-local IPv6), " +
            "link-local, carrier-grade NAT, multicast, unspecified, reserved and cloud metadata addresses are " +
            "refused (400 `webhook_host_not_allowed`), in numeric spellings too. A name is resolved again at " +
            "every delivery, through the server's pinned resolver: a name that answers such an address is " +
            "refused at the connection (`host_not_allowed` in the delivery log), DNS rebinding included. " +
            "Redirects are not followed. The operator may allow loopback and private targets " +
            "(`OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS=true`); link-local, unspecified and metadata addresses " +
            "stay refused. `types` names 1 to 64 event types of the feed (400 `invalid_webhook` for an unknown " +
            "one), `description` is at most 200 characters. A webhook starts at the newest event: nothing " +
            "earlier is sent. Past 20 webhooks, 409 `webhook_limit`. The secret (`whsec_` and 32 random bytes, " +
            "base64url) is in this answer only: it is stored sealed with the server's key and never readable " +
            "again."
        parameters {
            organizationHeader()
        }
        body<CreateWebhookRequest>("The target URL, the event types to send and an optional description.")
        responses {
            ok<CreatedWebhook>("The webhook, with its signing secret, shown this once.", HttpStatusCode.Created)
            badRequest(HOST)
            conflict("The organization already has 20 webhooks (`webhook_limit`).")
        }
    }
}

/** Reading, changing and removing a webhook. */
private fun ApiCatalog.webhookOperations() {
    get("/api/v1/webhooks/{id}") {
        operationId = "getWebhook"
        tag(WEBHOOKS_TAG)
        summary = "One outgoing webhook."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
        }
        responses {
            ok<Webhook>("The webhook.")
            notFound(NOT_FOUND)
        }
    }
    put("/api/v1/webhooks/{id}") {
        operationId = "updateWebhook"
        tag(WEBHOOKS_TAG)
        summary = "Change an outgoing webhook."
        description = "`version` is the one being replaced (409 `stale_version` otherwise). An omitted " +
            "`enabled` keeps the current state, so editing a paused or automatically disabled webhook never " +
            "enables it again; enabling it starts at the newest event (the pause is not replayed) and clears " +
            "its failure count. A webhook kept on its URL can still be paused, relabelled or deleted after the " +
            "operator withdrew private targets; a new URL must pass the address rule, and a paused one on a " +
            "refused URL cannot be enabled again (400 `webhook_host_not_allowed`)."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
        }
        body<UpdateWebhookRequest>("The version being replaced and the new URL, types, description and " +
            "state.")
        responses {
            ok<Webhook>("The webhook, changed.")
            badRequest(HOST)
            notFound(NOT_FOUND)
            conflict("The webhook changed since `version` (`stale_version`).")
        }
    }
    delete("/api/v1/webhooks/{id}") {
        operationId = "deleteWebhook"
        tag(WEBHOOKS_TAG)
        summary = "Remove an outgoing webhook and its delivery log."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
        }
        responses {
            empty("The webhook and its delivery log are removed.")
            notFound(NOT_FOUND)
        }
    }
}

/** The delivery log of a webhook, its test and the rotation of its secret. */
private fun ApiCatalog.webhookActionOperations() {
    get("/api/v1/webhooks/{id}/deliveries") {
        operationId = "listWebhookDeliveries"
        tag(WEBHOOKS_TAG)
        summary = "The delivery log of a webhook, newest first."
        description = "Each delivery is one event for one webhook: its state (`pending`, `delivered`, " +
            "`failed`), attempts, the next attempt while pending, the last HTTP status and a short error code " +
            "(`timeout`, `connect`, `tls`, `dns`, `host_not_allowed`, `secret_unreadable`, `expired`). The " +
            "receiver's answer is never read nor stored. The log is kept 14 days."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
            queryLimit("1 to 200, 50 by default.", 200)
        }
        responses {
            ok<WebhookDeliveryList>("The deliveries, newest first.")
            badRequest("A `limit` under 1 (`invalid_webhook`).")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/webhooks/{id}/test") {
        operationId = "testWebhook"
        tag(WEBHOOKS_TAG)
        summary = "Queue one signed `webhook.test` delivery."
        description = "Sent once, never retried, and never counted in the failures that disable a webhook; " +
            "its data is `{\"message\": ...}`. At most 3 tests in 10 seconds per webhook (429 " +
            "`webhook_test_rate`, `Retry-After: 10`)."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
        }
        responses {
            ok<WebhookDelivery>("The test delivery, queued.", HttpStatusCode.Accepted)
            notFound(NOT_FOUND)
            error(HttpStatusCode.TooManyRequests, "Three tests of this webhook in the last 10 seconds " +
                "(`webhook_test_rate`); `Retry-After` says when to try again.")
        }
    }
    post("/api/v1/webhooks/{id}/rotate-secret") {
        operationId = "rotateWebhookSecret"
        tag(WEBHOOKS_TAG)
        summary = "A new signing secret, shown once."
        description = "The former secret stops at once; every delivery still queued is signed with the new " +
            "one."
        parameters {
            organizationHeader()
            pathId("id", "The webhook's id.")
        }
        responses {
            ok<CreatedWebhook>("The webhook, with its new signing secret, shown this once.")
            notFound(NOT_FOUND)
        }
    }
}
