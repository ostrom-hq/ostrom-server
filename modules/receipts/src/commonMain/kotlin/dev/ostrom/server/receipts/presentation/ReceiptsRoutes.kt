// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.Pages
import dev.ostrom.server.kernel.http.ReadThrottle
import dev.ostrom.server.kernel.http.throttledReads
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.models.Verification
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The receipts routes of the contract: the keys are public (anyone verifies offline); the receipts are read by the
 * people of their organization only, with a receipts scope (S2-47), the organization coming from the credential.
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
                val orgId = call.reader(orgs)
                val limit = Pages.size(params.limit, ReceiptQuery.DEFAULT_PAGE, ReceiptQuery.MAX_PAGE)
                val query = ReceiptQuery(params.subjectKind, params.subjectId, params.before, limit)
                val items = listReceipts(orgId, query)
                // A full page may have a next one: its cursor is the last receipt of this one.
                val next = if (items.size == limit) items.last().id else null
                call.respond(ReceiptsPage(items.map { it.body }, next))
            }
            get<ReceiptsApi.Id> { params ->
                call.respond(getReceipt(call.reader(orgs), params.id))
            }
            get<ReceiptsApi.Id.Verify> { params ->
                val outcome = verifyReceipt(call.reader(orgs), params.parent.id)
                call.respond(outcome.toResponse())
            }
        }
    }
}

private fun Verification.toResponse() = VerificationResponse(ok, digest, kid, reason, orgBound)

// S2-47: a person with the receipts scope, then their organization.
private suspend fun ApplicationCall.reader(orgs: PrincipalOrgResolver): Uuid {
    requirePrincipal().requireReceiptReader()
    return requireOrgPrincipal(orgs, null).orgId
}
