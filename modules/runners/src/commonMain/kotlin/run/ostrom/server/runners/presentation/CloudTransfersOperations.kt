// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val TRANSFER_ID = "The transfer's id."

private const val NO_TRANSFER = "No such transfer, or one the caller is neither the owner nor the recipient of, or a " +
    "malformed id (`not_found`)."

private const val PERSON_OR_ON_BEHALF = "Not a person signed in: an agent or a service key never gives a session " +
    "away (`transfer_forbidden`). Or: $ON_BEHALF_FORBIDDEN"

/**
 * The ownership transfers (`runners.cloud.transfer.v1`), which the module mounts only on a server that wires them: a
 * server without them leaves these routes out.
 */
internal fun ApiCatalog.cloudTransferOperations() = conditionally {
    transferRecipients()
    transferOffer()
    transferListing()
    transferReading()
    transferAcceptance()
    transferDecision()
}

private fun ApiCatalog.transferRecipients() {
    get("/api/v1/cloud/transfer-recipients") {
        operationId = "listCloudTransferRecipients"
        tag(TAG)
        summary = "The members a session can be given to, for a picker."
        description = "`runners.cloud.transfer.v1`. Members of the organization who can own a session, by login, at " +
            "most 500, the caller included (an administrator may take a session over): only the id, the " +
            "login and whether the member is an administrator. An administrator, or a member while the " +
            "instance's `memberTransfers` is `offer` (403 `transfer_disabled` otherwise). A listed member " +
            "can still be refused for a given session, with 422 `recipient_ineligible`. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<CloudTransferRecipientsResponse>("The members, by login.")
            error(HttpStatusCode.Forbidden, "Members may not transfer on this instance (`transfer_disabled`).")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
}

private fun ApiCatalog.transferOffer() {
    post("/api/v1/cloud/sessions/{id}/transfer") {
        operationId = "transferCloudSession"
        tag(TAG)
        summary = "Give one of the caller's cloud sessions to another member."
        description = "`runners.cloud.transfer.v1`. Only the owner asks, signed in as a person. An " +
            "administrator's transfer is `accepted` at once; a member's is `offered` and the recipient " +
            "accepts within 7 days (403 `transfer_disabled` when the instance's `memberTransfers` is " +
            "`disabled`). Accepting pauses the session (the agent stops, its pod is gone); the owner changes " +
            "only then, and the workspace is marked untrusted for the recipient. At most one open transfer " +
            "per session (409 `transfer_pending`); a session still being created is 409 " +
            "`session_preparing`, an ended one 409 `session_not_running`; a recipient who cannot own the " +
            "session (disabled, not a member, at their cloud session limit) is 422 `recipient_ineligible` " +
            "naming every reason. `Idempotency-Key` is required (400 `idempotency_key_required`): a retry " +
            "returns the first transfer (`Idempotency-Replayed: true`), the same key with another request " +
            "is 422 `idempotency_conflict`. With `runners.cloud.onbehalf.writes.v1`, `Ostrom-On-Behalf-Of` " +
            "journals the member it names as the effective one, on the caller's own session, while the transfer " +
            "records the caller as its initiator, as on accept, decline and cancel. The body is at most 1 KiB. " +
            ORG_RESOLUTION
        parameters {
            onBehalfHeader(writes = true)
            organizationHeader()
            pathId("id", SESSION_ID)
            headerText(
                "Idempotency-Key",
                "Required: 1 to 128 characters of `A-Za-z0-9._:-`, one per give, reused for each retry of it.",
                required = true,
            )
        }
        body<CreateCloudTransferRequest>("The recipient, by member id or login, and whether the session resumes " +
            "for them once accepted.")
        responses {
            ok<CloudTransferResponse>("The transfer, asked (or the first one of this key, replayed).",
                HttpStatusCode.Accepted)
            badRequest("No `Idempotency-Key`, or a malformed one (`idempotency_key_required`), or a recipient " +
                "that is not a member id or a login (`invalid_transfer`).")
            error(HttpStatusCode.Forbidden, "$PERSON_OR_ON_BEHALF Or members may not transfer on this instance " +
                "(`transfer_disabled`).")
            notFound(NO_SESSION)
            conflict("The session already has an open transfer (`transfer_pending`), is still being created " +
                "(`session_preparing`), is over (`session_not_running`), or is being transferred " +
                "(`session_transferring`).")
            error(HttpStatusCode.UnprocessableEntity, "The recipient cannot own the session " +
                "(`recipient_ineligible`), the key was used with another request (`idempotency_conflict`), or " +
                "`Ostrom-On-Behalf-Of` names no active member (`invalid_effective_member`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.transferListing() {
    get("/api/v1/cloud/transfers") {
        operationId = "listCloudTransfers"
        tag(TAG)
        summary = "The caller's transfers, incoming and outgoing, newest first."
        description = "`runners.cloud.transfer.v1`. A transfer is visible to its two members only. An offer past its " +
            "validity reads `expired` before the server records it. A page of `limit` transfers (50 by " +
            "default) is followed by `next` while there may be more. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            queryText("direction", "`incoming`, `outgoing` or `all` (the default).")
            queryText("state", "Only the transfers in this state; repeat it for several.")
            queryText("session", "Only the transfers of this session.")
            queryText("before", "The `next` of the previous page.")
            queryNumber("limit", "How many transfers a page holds.")
        }
        responses {
            ok<CloudTransferPageResponse>("A page of transfers, newest first, with the cursor of the next.")
            badRequest("An unknown `direction`, `state` or `session`, or a malformed cursor (`invalid_request`).")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
}

private fun ApiCatalog.transferReading() {
    get("/api/v1/cloud/transfers/{id}") {
        operationId = "getCloudTransfer"
        tag(TAG)
        summary = "One transfer."
        description = "`runners.cloud.transfer.v1`. 404 for anyone but the two members. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", TRANSFER_ID)
        }
        responses {
            ok<CloudTransferResponse>("The transfer.")
            notFound(NO_TRANSFER)
        }
    }
}

private fun ApiCatalog.transferAcceptance() {
    post("/api/v1/cloud/transfers/{id}/accept") {
        operationId = "acceptCloudTransfer"
        tag(TAG)
        summary = "Accept a session offered to the caller."
        description = "`runners.cloud.transfer.v1`. Only the recipient, signed in as a person. The session pauses, " +
            "then becomes the recipient's; before it runs again the runner resets the agent's settings and " +
            "the repositories' Git configuration (`untrustedWorkspace`). Idempotent. The body is optional: " +
            "a bare POST keeps what the offer asked for. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader(writes = true)
            organizationHeader()
            pathId("id", TRANSFER_ID)
        }
        body<AcceptCloudTransferRequest>("Whether the session resumes for the recipient once accepted; leave it " +
            "out to keep what the offer asked for.", required = false)
        responses {
            ok<CloudTransferResponse>("Accepted, the transfer is under way.", HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, PERSON_OR_ON_BEHALF)
            notFound(NO_TRANSFER)
            conflict("The offer expired (`transfer_expired`), or the transfer is decided already " +
                "(`transfer_not_open`).")
            error(HttpStatusCode.UnprocessableEntity, "The recipient can no longer own the session " +
                "(`recipient_ineligible`), or `Ostrom-On-Behalf-Of` names no active member " +
                "(`invalid_effective_member`).")
        }
    }
}

private fun ApiCatalog.transferDecision() {
    post("/api/v1/cloud/transfers/{id}/decline") {
        operationId = "declineCloudTransfer"
        tag(TAG)
        summary = "Decline an offered session."
        description = "`runners.cloud.transfer.v1`. Only the recipient, signed in as a person; only an offered " +
            "transfer. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader(writes = true)
            organizationHeader()
            pathId("id", TRANSFER_ID)
        }
        responses {
            ok<CloudTransferResponse>("The transfer, declined.")
            error(HttpStatusCode.Forbidden, PERSON_OR_ON_BEHALF)
            notFound(NO_TRANSFER)
            conflict("The transfer is decided already (`transfer_not_open`).")
            error(HttpStatusCode.UnprocessableEntity, ON_BEHALF_INVALID_MEMBER)
        }
    }
    post("/api/v1/cloud/transfers/{id}/cancel") {
        operationId = "cancelCloudTransfer"
        tag(TAG)
        summary = "Withdraw an offer, or cancel an accepted transfer before the owner change starts."
        description = "`runners.cloud.transfer.v1`. Only the session's former owner, signed in as a person; an " +
            "accepted transfer only by an administrator, and not once the session is `transferring` (409 " +
            "`transfer_in_progress`). $ORG_RESOLUTION"
        parameters {
            onBehalfHeader(writes = true)
            organizationHeader()
            pathId("id", TRANSFER_ID)
        }
        responses {
            ok<CloudTransferResponse>("The transfer, cancelled.")
            error(HttpStatusCode.Forbidden, PERSON_OR_ON_BEHALF)
            notFound(NO_TRANSFER)
            conflict("The transfer is decided already (`transfer_not_open`), or the owner is being changed " +
                "(`transfer_in_progress`).")
            error(HttpStatusCode.UnprocessableEntity, ON_BEHALF_INVALID_MEMBER)
        }
    }
}
