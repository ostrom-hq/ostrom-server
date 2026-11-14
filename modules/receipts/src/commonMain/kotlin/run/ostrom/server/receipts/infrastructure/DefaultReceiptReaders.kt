// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.services.ReceiptOwners
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import kotlin.uuid.Uuid

/**
 * The readers while no gates module is loaded: a person is resolved by their organization and role only, with no
 * login, so they read the receipts about no session only, whatever their role; nobody approves.
 *
 * @param orgs Resolves the caller's organization.
 */
class OrgReceiptReaders(private val orgs: PrincipalOrgResolver) : ReceiptReaders {
    override suspend fun resolve(principal: AuthenticatedPrincipal): ReceiptReader? =
        orgs.resolve(principal, null)?.let { ReceiptReader(it.orgId, null) }

    override suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean = false

    override suspend fun approvedSessions(orgId: Uuid, person: String): List<String> = emptyList()
}

/** No session module: a receipt names no person. */
class NoReceiptOwners : ReceiptOwners {
    override suspend fun ownerOf(orgId: Uuid, sessionId: String): String? = null
}
