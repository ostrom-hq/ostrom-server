// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.presentation

import run.ostrom.core.api.models.Receipt
import run.ostrom.core.api.models.ReceiptPage
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText
import run.ostrom.server.kernel.http.docs.rateLimited

private const val TAG = "receipts"

private const val RECEIPT_ID = "The receipt's id."

private const val NOT_FOUND = "No such receipt in the caller's organization, or one the caller may not read " +
    "(`not_found`)."

/**
 * The receipts module's operations. A receipt is answered as signed, its bytes as stored: the schema is ostrom-core's
 * [Receipt], the shape a client verifies.
 */
internal fun ApiCatalog.receiptsOperations() {
    tag(TAG, "The signed receipts of sessions, packages and decisions, and the keys that verify them offline.")
    get("/api/v1/receipts/keys") {
        operationId = "listReceiptKeys"
        tag(TAG)
        summary = "Public keys of the instance."
        description = "Every key the instance has signed receipts with, each with the signature of its rotation by " +
            "the key before it, so anyone verifies a receipt offline."
        responses { ok<ReceiptKeysResponse>("The keys.") }
    }
    receiptReadOperations()
}

private fun ApiCatalog.receiptReadOperations() {
    get("/api/v1/receipts") {
        operationId = "listReceipts"
        tag(TAG)
        summary = "Receipts."
        description = "The receipts of the caller's organization, newest first: a person with a receipts scope reads " +
            "their own sessions', the owners and administrators every one, the approvers of a gate its subject's."
        parameters {
            organizationHeader()
            queryText("subjectKind", "Only receipts of this kind of subject.")
            queryText("subjectId", "Only receipts of this subject.")
            queryText("before", "The cursor of the next page (`next` of the previous one).")
            queryLimit("The page size, 1 to 200 (50 by default); 400 under 1, a larger one is brought down to 200.", maximum = 200)
        }
        responses {
            ok<ReceiptPage>("A page of receipts, `next` set when another may follow.")
            badRequest("An unknown subject kind or cursor (`invalid_request`).")
            rateLimited()
        }
    }
    get("/api/v1/receipts/{id}") {
        operationId = "getReceipt"
        tag(TAG)
        summary = "A receipt."
        parameters {
            organizationHeader()
            pathId("id", RECEIPT_ID)
        }
        responses {
            ok<Receipt>("The receipt, as signed.")
            notFound(NOT_FOUND)
            rateLimited()
        }
    }
    get("/api/v1/receipts/{id}/verify") {
        operationId = "verifyReceipt"
        tag(TAG)
        summary = "Verify a receipt with the instance keys."
        description = "The server's own check of the receipt it stores: its digest, the key that signed it, and " +
            "whether it is bound to the caller's organization. A client verifies offline with the keys of " +
            "`listReceiptKeys` instead of trusting this answer."
        parameters {
            organizationHeader()
            pathId("id", RECEIPT_ID)
        }
        responses {
            ok<VerificationResponse>("Whether the receipt verifies, and why not.")
            notFound(NOT_FOUND)
            rateLimited()
        }
    }
}
