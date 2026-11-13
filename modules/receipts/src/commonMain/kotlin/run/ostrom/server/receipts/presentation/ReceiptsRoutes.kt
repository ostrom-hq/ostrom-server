// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.presentation

import run.ostrom.server.kernel.auth.OrganizationNotFoundException
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.kernel.http.ReadThrottle
import run.ostrom.server.kernel.http.throttledReads
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.Verification
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The receipts routes of the contract: the keys are public (anyone verifies offline); the receipts are read by the
 * people of their organization only, with a receipts scope (S2-47), the organization coming from the credential: a
 * session's by its person, the owners and administrators, and the approvers of its gates (TeamCode #238); any other
 * is answered as absent.
 *
 * @param dependencies The use cases.
 * @param throttle Bounds the reads of receipts per caller (AUDIT-SECU-LECTURES M1).
 */
fun Route.receiptsRoutes(dependencies: ReceiptsRoutesDependencies, throttle: ReadThrottle) = with(dependencies) {
    get<ReceiptsApi.Keys> {
        val keys = listKeys().map { key ->
            val rotation = key.rotation?.let { KeyRotationResponse(it.kid, it.signature) }
            PublicKeyResponse(key.kid, key.algorithm, key.publicKeyPem, rotation)
        }
        call.respond(ReceiptKeysResponse(keys))
    }
    authenticated {
        throttledReads(throttle) {
            get<ReceiptsApi> { params ->
                val reader = call.reader(readers)
                val limit = Pages.size(params.limit, ReceiptQuery.DEFAULT_PAGE, ReceiptQuery.MAX_PAGE)
                val query = ReceiptQuery(params.subjectKind, params.subjectId, params.before, limit)
                val items = listReceipts(reader, query)
                // A full page may have a next one: its cursor is the last receipt of this one.
                val next = if (items.size == limit) items.last().id else null
                call.respond(ReceiptsPage(items.map { it.body }, next))
            }
            get<ReceiptsApi.Id> { params ->
                call.respond(getReceipt(call.reader(readers), params.id))
            }
            get<ReceiptsApi.Id.Verify> { params ->
                val outcome = verifyReceipt(call.reader(readers), params.parent.id)
                call.respond(outcome.toResponse())
            }
        }
    }
}

private fun Verification.toResponse() = VerificationResponse(ok, digest, kid, reason, orgBound)

// S2-47: a person with the receipts scope, then who they are in their organization (TeamCode #238).
private suspend fun ApplicationCall.reader(readers: ReceiptReaders): ReceiptReader {
    val principal = requirePrincipal()
    principal.requireReceiptReader()
    return readers.resolve(principal) ?: throw OrganizationNotFoundException()
}
