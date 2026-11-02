// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.auth.GovernanceRequest
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Opens a confirmation for a governance write a person's token asked for (audit CLI H1). */
interface OpenConfirmationUseCase {
    /**
     * Opens it, or answers the one the same person already waits for with the same write.
     *
     * @param request The write, who asked and where.
     *
     * @return The confirmation.
     *
     * @throws ConfirmationNotFoundException When the person is not a member of the organization (404, as an unknown
     *   organization).
     * @throws ConfirmationManagersOnlyException When the person is not an owner or an admin.
     * @throws ConfirmationLimitException When they already wait for [ConfirmationRules.MAX_WAITING].
     * @throws ConfirmationAuditUnavailableException When the server keeps no audit log.
     * @throws dev.ostrom.server.kernel.errors.OstromException The owning module's refusal of the write itself.
     */
    suspend operator fun invoke(request: GovernanceRequest): Confirmation
}

/**
 * [OpenConfirmationUseCase]: the owning module previews the write (what it replaces and what it writes), the step is
 * recorded in the audit log, then the confirmation is stored for [ConfirmationRules.LIFETIME].
 *
 * @param repository The confirmations.
 * @param members Resolves the person's membership.
 * @param changes The modules' governance writes.
 * @param audit The audit log.
 * @param expire Expires the organization's overdue confirmations first.
 * @param clock The time.
 */
class OpenConfirmationUseCaseImpl(
    private val repository: ConfirmationsRepository,
    private val members: PrincipalOrgResolver,
    private val changes: GovernanceChanges,
    private val audit: ConfirmationAudit,
    private val expire: ExpireConfirmationsUseCase,
    private val clock: Clock,
) : OpenConfirmationUseCase {
    override suspend fun invoke(request: GovernanceRequest): Confirmation {
        val member = members.resolve(request.principal, request.orgId)?.takeIf { it.orgId == request.orgId }
            ?: throw ConfirmationNotFoundException()
        if (member.role !in ConfirmationRules.MANAGERS) throw ConfirmationManagersOnlyException()
        expire.inOrganization(request.orgId)
        val userId = request.principal.subjectId
        val waiting = repository.waiting(request.orgId, userId, clock.now())
        waiting.firstOrNull { it.asksFor(request.write, request.method, request.path) }?.let { return it }
        if (waiting.size >= ConfirmationRules.MAX_WAITING) throw ConfirmationLimitException()
        val preview = changes.of(request.write.kind).preview(request.orgId, request.write)
        val now = clock.now()
        val confirmation = Confirmation(
            id = "gcf_" + Uuid.random().toHexString(),
            orgId = request.orgId,
            write = request.write,
            scope = request.scope,
            method = request.method,
            path = request.path,
            summary = preview.summary,
            before = preview.before,
            after = preview.after,
            requestedBy = ConfirmationPerson(userId, member.memberId),
            credential = if (request.principal.scopes.any { it in GovernanceScopes.ALL }) {
                ConfirmationCredential.ADMIN
            } else {
                ConfirmationCredential.DEVICE
            },
            tokenId = request.principal.credentialId,
            status = ConfirmationStatus.PENDING,
            createdAt = now,
            expiresAt = now + ConfirmationRules.LIFETIME,
        )
        // Recorded before it is stored: without an audit log, no confirmation exists to be confirmed.
        audit.record(confirmation, ConfirmationStep.CREATED, userId)
        repository.insert(confirmation)
        return confirmation
    }
}
