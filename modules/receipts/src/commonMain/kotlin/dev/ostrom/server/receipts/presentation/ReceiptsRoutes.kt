package dev.ostrom.server.receipts.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptsUseCase
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route


/**
 * What the receipts routes need.
 *
 * @property orgs Resolves the caller's organization.
 * @property getReceipt Reads a receipt.
 * @property listReceipts Lists receipts.
 * @property verifyReceipt Verifies a receipt.
 * @property listKeys Lists the public keys.
 */
data class ReceiptsRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val getReceipt: GetReceiptUseCase,
    val listReceipts: ListReceiptsUseCase,
    val verifyReceipt: VerifyReceiptUseCase,
    val listKeys: ListReceiptKeysUseCase,
)

/**
 * The receipts routes of the contract: the keys are public (anyone verifies offline); the receipts are read by the
 * members of their organization only, the organization coming from the credential.
 *
 * @param dependencies The use cases.
 */
fun Route.receiptsRoutes(dependencies: ReceiptsRoutesDependencies) = with(dependencies) {
    get<ReceiptsApi.Keys> {
        call.respond(ReceiptKeysResponse(listKeys().map { PublicKeyResponse(it.kid, it.algorithm, it.publicKeyPem) }))
    }
    authenticated {
        get<ReceiptsApi> { params ->
            val orgId = call.requireOrgPrincipal(orgs, null).orgId
            val limit = (params.limit ?: ReceiptQuery.DEFAULT_PAGE).coerceIn(1, ReceiptQuery.MAX_PAGE)
            val items = listReceipts(orgId, ReceiptQuery(params.subjectKind, params.subjectId, params.before, limit))
            // A full page may have a next one: its cursor is the last receipt of this one.
            val next = if (items.size == limit) items.last().id else null
            call.respond(ReceiptsPage(items.map { it.body }, next))
        }
        get<ReceiptsApi.Id> { params ->
            call.respond(getReceipt(call.requireOrgPrincipal(orgs, null).orgId, params.id))
        }
        get<ReceiptsApi.Id.Verify> { params ->
            val outcome = verifyReceipt(call.requireOrgPrincipal(orgs, null).orgId, params.parent.id)
            call.respond(VerificationResponse(outcome.ok, outcome.digest, outcome.kid, outcome.reason))
        }
    }
}
