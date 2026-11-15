// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import io.ktor.http.HttpStatusCode

private const val TAG = "governance"

private const val ID = "The confirmation's id."

private const val NOT_FOUND = "No such confirmation in the caller's organization, or one the caller may not read " +
    "(`not_found`)."

/**
 * The governance module's operations: reading a confirmation of a governance write made with a person's token, and
 * answering it from the console. The answers are the module's own types, which follow the contract's
 * `GovernanceConfirmation` field for field.
 */
internal fun ApiCatalog.confirmationsOperations() {
    tag(TAG, "The confirmations of governance changes: a write made with a person's token waits until an owner " +
        "or an admin confirms it on the console.")
    readOperations()
    answerOperations()
}

/** Reading a confirmation. */
private fun ApiCatalog.readOperations() {
    get("/api/v1/confirmations/{id}") {
        operationId = "getGovernanceConfirmation"
        tag(TAG)
        summary = "A governance confirmation, with the exact change it makes."
        description = "Read by its requester (with any of their credentials) and by the owners and admins of " +
            "its organization; anyone else, and another organization, gets 404. A pending confirmation past " +
            "its 15 minutes reads as `expired`. With `wait`, a pending confirmation is waited on for its " +
            "answer: the response comes as soon as it is no longer pending, or with it still pending when the " +
            "wait ends; the caller then asks again until `expiresAt`. A confirmation that is not pending " +
            "answers at once. The wait is 0 to 30 seconds (a server given a value outside that range waits " +
            "for the nearest bound; a client sends one within it). A waiting request holds no database " +
            "connection. A client sets its HTTP timeout above the wait."
        parameters {
            organizationHeader()
            pathId("id", ID)
            queryNumber("wait", "Seconds to wait on a pending confirmation for its answer, 0 to 30 (a value " +
                "outside is brought to the nearest bound); absent or 0 reads at once.")
        }
        responses {
            ok<ConfirmationDto>("The confirmation as it stands, with the change it makes.")
            notFound(NOT_FOUND)
        }
    }
}

/** Answering a confirmation. */
private fun ApiCatalog.answerOperations() {
    post("/api/v1/confirmations/{id}/answer") {
        operationId = "answerGovernanceConfirmation"
        tag(TAG)
        summary = "Confirm or refuse a governance confirmation, as an owner or an admin signed in to the console."
        description = "Only a browser session answers (a token gets 403 `forbidden`), of an owner or an admin " +
            "of the organization (403 `forbidden` otherwise), once (409 `confirmation_closed`), within its 15 " +
            "minutes (410 `confirmation_expired`). `confirm` needs a sign-in less than 5 minutes old (403 " +
            "`step_up_required`: sign in again, then confirm), as a yes does; `refuse` does not. A " +
            "confirmation applies the change exactly as it was shown, as a signed decision of the person who " +
            "confirms (the requester is kept in the confirmation and the audit log). When what it changes is " +
            "no longer what it showed as `before`, nothing is written and the confirmation closes as " +
            "`failed` with 409 `confirmation_stale`. When the write itself is refused (a version conflict, a " +
            "rule deleted meanwhile), the confirmation closes as `failed` with that error, and the answer is " +
            "that error. When it fails for a reason the server does not show, the answer is 500 " +
            "`internal_error` and the confirmation closes as `failed` with `internal_error`. Each step " +
            "(created, confirmed, refused, expired, failed) is an event of the organization's signed audit " +
            "log."
        parameters {
            organizationHeader()
            pathId("id", ID)
        }
        body<AnswerConfirmationRequest>("The answer: `confirm` or `refuse`.")
        responses {
            ok<ConfirmationDto>("The answered confirmation; when confirmed, `result` holds what the write " +
                "returned.")
            badRequest("An `answer` that is neither `confirm` nor `refuse` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "A token, or a member who is neither an owner nor an admin " +
                "(`forbidden`), or a sign-in more than 5 minutes old for `confirm` (`step_up_required`).")
            notFound(NOT_FOUND)
            conflict("Already answered (`confirmation_closed`), what it replaces changed since it was asked " +
                "(`confirmation_stale`), or the confirmed write's own error (key `error.type`).")
            error(HttpStatusCode.Gone, "It expired unanswered (`confirmation_expired`).")
        }
    }
}
