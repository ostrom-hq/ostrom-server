// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.usecases.SessionApproversUseCase
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.TEAM_READERS
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import kotlin.uuid.Uuid

/**
 * The receipts' readers on the gates: a person is resolved as a gate's actor (their organization, their login, their
 * role), and the approvers of a session's gates read its receipts (TeamCode #238).
 *
 * @param actors Resolves the caller; lazy, since the gates module binds its default after the receipts module looks
 *   for this.
 * @param approvers The approvers of the sessions' gates.
 * @param organizations Resolves a person no actor resolver knows (no launches module loaded) by their organization
 *   and role alone, with no login: they then read the receipts about no session only, unless an owner or an admin.
 */
class GatesReceiptReaders(
    private val actors: Lazy<GateActorResolver>,
    private val approvers: SessionApproversUseCase,
    private val organizations: ReceiptReaders,
) : ReceiptReaders {
    override suspend fun resolve(principal: AuthenticatedPrincipal): ReceiptReader? =
        actors.value.resolve(principal)?.let { ReceiptReader(it.orgId, it.personId, it.role in TEAM_READERS) }
            ?: organizations.resolve(principal)

    override suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean =
        approvers.approves(orgId, person, sessionId)

    override suspend fun approvedSessions(orgId: Uuid, person: String): List<String> = approvers(orgId, person)
}
