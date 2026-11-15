// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "budgets"

private const val BAD_QUERY = "A malformed date, member id, cursor or page (`invalid_request`)."

private const val REVIEW_NOT_FOUND = "No such call under review in the caller's organization (`not_found`)."

private const val STREAM = "The event stream (`text/event-stream`): a `: connected` comment once " +
    "subscribed, then one `data:` frame per event."

/**
 * The usage module's operations: the ledger of the organization's spending, the events clients record, the live
 * stream, and the review of usage whose final amount is unknown.
 */
internal fun ApiCatalog.usageOperations() {
    tag(TAG, "Spending, usage, and the limits and alerts that bound them.")
    ledgerOperations()
    eventOperations()
    pendingOperations()
    reconciliationOperations()
    reconcileOperations()
}

/** The ledger and its summary. */
private fun ApiCatalog.ledgerOperations() {
    get("/api/v1/usage") {
        operationId = "getUsage"
        tag(TAG)
        summary = "Spending over a period."
        description = "The ledger of the caller's organization: an admin reads the whole organization, a " +
            "member only their own usage."
        parameters {
            organizationHeader()
            queryText("from", "The start: an ISO 8601 instant or date (UTC); 30 days before `to` when " +
                "absent.")
            queryText("to", "The end, excluded; now when absent.")
            queryText("member", "For an admin, one member only.")
        }
        responses {
            ok<UsageResponse>("The spending over the period.")
            badRequest(BAD_QUERY)
        }
    }
    get("/api/v1/usage/summary") {
        operationId = "getUsageSummary"
        tag(TAG)
        summary = "Totals of the last days per group, most expensive first."
        description = "An admin reads the organization, a member their own usage."
        parameters {
            organizationHeader()
            queryNumber("days", "The period, 1 to 90 days (7 by default).")
            queryText("groupBy", "The grouping: `member` (the default), `kind`, `model`, `provider` or " +
                "`account`.")
        }
        responses {
            ok<UsageSummaryResponse>("The groups, most expensive first, and their total.")
            badRequest(BAD_QUERY)
        }
    }
}

/** The events of the ledger: reading them, recording them, and following them live. */
private fun ApiCatalog.eventOperations() {
    get("/api/v1/usage/events") {
        operationId = "listUsageEvents"
        tag(TAG)
        summary = "The usage events, newest first."
        parameters {
            organizationHeader()
            queryText("before", "The `next` of the previous page, or an ISO 8601 time.")
            queryLimit("The page size, 1 to 200 (50 by default).", 200)
        }
        responses {
            ok<UsageEventsPageResponse>("A page of events, `next` set when another may follow.")
            badRequest(BAD_QUERY)
        }
    }
    post("/api/v1/usage/events") {
        operationId = "recordUsageEvents"
        tag(TAG)
        summary = "Record the client's events (skills, hooks, sessions, MCP calls)."
        description = "The organization, the member and the cost always come from the server. At most 500 events."
        parameters {
            organizationHeader()
        }
        body<UsageEventsRequest>("At most 500 events: the organization, the member and the cost always come " +
            "from the server.")
        responses {
            ok<UsageAcceptedResponse>("The number of events recorded.", HttpStatusCode.Accepted)
            badRequest("A body that is not a list of usage events, or an event that is malformed or of a " +
                "kind only the server records (`invalid_request`).")
            error(HttpStatusCode.PayloadTooLarge, "More than 500 events in the request " +
                "(`payload_too_large`).")
            error(HttpStatusCode.TooManyRequests, "More than 6 000 events recorded this minute by the " +
                "member (`rate_limited`).")
        }
    }
    get("/api/v1/usage/live") {
        operationId = "streamUsage"
        tag(TAG)
        summary = "Server-sent events of the new usage events."
        description = "`: connected` once subscribed, then one `data:` line per event (a `UsageEvent` in JSON) and " +
            "a `: heartbeat` comment at intervals. A stream ends after a while and the EventSource reconnects, " +
            "which checks the caller again."
        parameters {
            organizationHeader()
        }
        responses {
            empty(STREAM, HttpStatusCode.OK)
        }
    }
}

/** The usage awaiting review. */
private fun ApiCatalog.pendingOperations() {
    get("/api/v1/usage/pending") {
        operationId = "listPendingUsage"
        tag(TAG)
        summary = "Unknown usage awaiting review (`capacity.usagereconciliation.v1`)."
        description = "TeamCode's Usage review. A model call whose final usage never reached the ledger (a " +
            "stream cut, a replica lost) keeps its conservative charge, the cost it was admitted for, instead " +
            "of counting as zero: its hold has run out and stays charged to the day it was held. This lists " +
            "those holds, newest first, 50 at most a page (`next` is the `before` of the following page). " +
            "`before` is the `id` of the last item of the previous page; `member` keeps one member's. A hold " +
            "still within its lease is not listed. Owners and admins, a person signed in; a member, a key (an " +
            "administrator's included) or an agent: 403. Never cached."
        parameters {
            organizationHeader()
            queryText("member", "Only this member's (`OrgMember.id`).")
            queryText("before", "The `id` of the last item of the previous page.")
        }
        responses {
            ok<PendingUsagePage>("A page of unknown usage, newest first, `next` set when another may " +
                "follow.")
            badRequest(BAD_QUERY)
        }
    }
}

/** The history of corrections, and one hold under review or its correction. */
private fun ApiCatalog.reconciliationOperations() {
    get("/api/v1/usage/reconciliations") {
        operationId = "listUsageReconciliations"
        tag(TAG)
        summary = "Reconciliation history (`capacity.usagereconciliation.v1`)."
        description = "The corrections made to unknown usage, newest first, 50 at most a page, kept as long " +
            "as their holds (90 days). Same paging, filters and authority as `listPendingUsage`."
        parameters {
            organizationHeader()
            queryText("member", "Only this member's (`OrgMember.id`).")
            queryText("before", "The `id` of the last item of the previous page.")
        }
        responses {
            ok<UsageReconciliationsPage>("A page of corrections, newest first, `next` set when another may " +
                "follow.")
            badRequest(BAD_QUERY)
        }
    }
    get("/api/v1/usage/reservations/{id}") {
        operationId = "getUsageReview"
        tag(TAG)
        summary = "One hold under review, or its correction (`capacity.usagereconciliation.v1`)."
        description = "`pending` while the hold is still unknown, `reconciliation` once an administrator " +
            "corrected it (the two are never both present). 404 for an id that is neither. The id is a resource " +
            "identifier, not a capability. Same authority as `listPendingUsage`."
        parameters {
            organizationHeader()
            pathId("id", "The hold's id, the call's.")
        }
        responses {
            ok<UsageReviewDto>("The hold under review (`pending`) or its correction (`reconciliation`).")
            notFound(REVIEW_NOT_FOUND)
        }
    }
}

/** Correcting an unknown charge. */
private fun ApiCatalog.reconcileOperations() {
    post("/api/v1/usage/reservations/{id}/reconcile") {
        operationId = "reconcileUsage"
        tag(TAG)
        summary = "Replace an unknown charge with what the provider billed " +
            "(`capacity.usagereconciliation.v1`)."
        description = "An administrator's attestation of provider evidence for that exact call; the server " +
            "does not fetch or verify the record. Only a hold whose lease has run out and that no ledger line " +
            "settled can be corrected, once: a final correction, not a refund of known usage. `expectedMicros` " +
            "must equal the charge displayed (`PendingUsage.micros`) and `expectedProviderRequestId` the " +
            "displayed provider request id (absent when none was captured), else 409 `stale_reconciliation` and " +
            "the view must be reviewed again. `micros` is the verified total (0 to ${"$"}100 000 000), the same " +
            "correction again answers the original record without another delta, a different one 409 " +
            "`reconciled`. The correction applies to the day the hold was held and to the member, workflow and " +
            "launch it was attributed to then; it creates nothing on today's ledger. Audited " +
            "(`usage.reconciled`). Owners and admins, a person signed in (a sign-in less than 5 minutes old)."
        parameters {
            organizationHeader()
            pathId("id", "The hold's id, the call's.")
        }
        body<ReconcileUsageRequest>("The charge and request id as the reviewer saw them, the verified " +
            "total, the kind of evidence, its reference and the reason.")
        responses {
            ok<UsageReconciliationDto>("The correction, as made now or by an earlier identical call.")
            badRequest("A kind of evidence that is not request-specific provider evidence, or a total, " +
                "reference or reason out of bounds (`invalid_reconciliation`).")
            error(HttpStatusCode.Forbidden, "The caller is not an owner or an administrator, is not a " +
                "person, or signed in more than 5 minutes ago (`step_up_required`).")
            notFound(REVIEW_NOT_FOUND)
            conflict("The charge or the provider request id changed since the reviewer saw them " +
                "(`stale_reconciliation`), or another correction already resolved the call (`reconciled`).")
        }
    }
}
