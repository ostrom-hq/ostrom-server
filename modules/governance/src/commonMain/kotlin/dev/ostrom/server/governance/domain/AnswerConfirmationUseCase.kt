// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** An owner or an admin confirms or refuses a governance change on the console. */
interface AnswerConfirmationUseCase {
    /**
     * Answers it. A confirmation re-reads what the change replaces, then makes the write as the confirmer's decision.
     *
     * @param confirmer Who answers: a person's browser session.
     * @param id The confirmation.
     * @param answer Confirm or refuse.
     *
     * @return The confirmation, as it stands after the answer.
     *
     * @throws BrowserSessionRequiredException When the credential is not a browser session (a token never answers).
     * @throws ConfirmationNotFoundException When the confirmer's organization has no such confirmation.
     * @throws ConfirmationManagersOnlyException When the confirmer is not an owner or an admin.
     * @throws ConfirmationExpiredException When it expired.
     * @throws ConfirmationClosedException When it was already answered.
     * @throws ConfirmationStepUpRequiredException When a confirmation comes from a sign-in more than 5 minutes old.
     * @throws ConfirmationStaleException When what it replaces changed since it was asked: closed, nothing written.
     * @throws OstromException The write's own refusal: closed as failed with it.
     */
    suspend operator fun invoke(
        confirmer: AuthenticatedPrincipal,
        id: String,
        answer: ConfirmationAnswer,
    ): Confirmation
}

/**
 * [AnswerConfirmationUseCase]: one answer only (the repository changes a pending confirmation once), each step in the
 * audit log before what it allows, and the reads waiting on it woken.
 *
 * @param repository The confirmations.
 * @param members Resolves the confirmer's membership.
 * @param changes The modules' governance writes.
 * @param audit The audit log.
 * @param expire Writes the expiry of the overdue ones first.
 * @param waits Wakes the reads waiting on it.
 * @param clock The time.
 */
class AnswerConfirmationUseCaseImpl(
    private val repository: ConfirmationsRepository,
    private val members: PrincipalOrgResolver,
    private val changes: GovernanceChanges,
    private val audit: ConfirmationAudit,
    private val expire: ExpireConfirmationsUseCase,
    private val waits: ConfirmationWaits,
    private val clock: Clock,
) : AnswerConfirmationUseCase {
    override suspend fun invoke(
        confirmer: AuthenticatedPrincipal,
        id: String,
        answer: ConfirmationAnswer,
    ): Confirmation {
        if (confirmer.kind != PrincipalKind.USER || confirmer.channel != PrincipalChannel.BROWSER) {
            throw BrowserSessionRequiredException()
        }
        val member = members.resolve(confirmer, null) ?: throw ConfirmationNotFoundException()
        expire.inOrganization(member.orgId)
        val confirmation = repository.get(member.orgId, id) ?: throw ConfirmationNotFoundException()
        if (member.role !in ConfirmationRules.MANAGERS) throw ConfirmationManagersOnlyException()
        requirePending(confirmation)
        val by = ConfirmationPerson(confirmer.subjectId, member.memberId)
        return when (answer) {
            ConfirmationAnswer.REFUSE -> refuse(confirmation, by)
            ConfirmationAnswer.CONFIRM -> confirm(confirmation, by, GovernanceConfirmer(confirmer, member))
        }
    }

    private suspend fun refuse(confirmation: Confirmation, by: ConfirmationPerson): Confirmation {
        close(confirmation, ConfirmationStatus.REFUSED, by)
        return finish(confirmation, ConfirmationStep.REFUSED, by)
    }

    private suspend fun confirm(
        confirmation: Confirmation,
        by: ConfirmationPerson,
        confirmer: GovernanceConfirmer,
    ): Confirmation {
        val authenticatedAt = confirmer.principal.authenticatedAt
        if (authenticatedAt == null || clock.now() - authenticatedAt > ConfirmationRules.STEP_UP_WITHIN) {
            throw ConfirmationStepUpRequiredException()
        }
        val change = changes.of(confirmation.write.kind)
        val orgId = confirmation.orgId
        // What it replaces is read again: a change confirmed on what it showed, never on what it shows now.
        val now = try {
            change.preview(orgId, confirmation.write)
        } catch (refused: OstromException) {
            fail(confirmation, by, refused)
        }
        if (now.before != confirmation.before) fail(confirmation, by, ConfirmationStaleException())
        close(confirmation, ConfirmationStatus.CONFIRMED, by)
        val result = try {
            audit.record(confirmation, ConfirmationStep.CONFIRMED, by.userId)
            change.apply(orgId, confirmation.write, confirmer.inOrganization(orgId))
        } catch (refused: OstromException) {
            val error = ConfirmationError(refused.type, refused.message)
            repository.settle(orgId, confirmation.id, null, error)
            recordFailure(confirmation, by)
            throw refused
        }
        repository.settle(orgId, confirmation.id, result, null)
        return finish(confirmation, null, by)
    }

    // Closed as failed with [refused], which the confirmer gets: nothing was written.
    private suspend fun fail(confirmation: Confirmation, by: ConfirmationPerson, refused: OstromException): Nothing {
        close(confirmation, ConfirmationStatus.FAILED, by, ConfirmationError(refused.type, refused.message))
        recordFailure(confirmation, by)
        throw refused
    }

    private suspend fun recordFailure(confirmation: Confirmation, by: ConfirmationPerson) {
        waits.changed(confirmation.id)
        audit.record(read(confirmation), ConfirmationStep.FAILED, by.userId)
    }

    // Changes it once: a second answer, here or on another instance, finds it already answered or expired.
    private suspend fun close(
        confirmation: Confirmation,
        status: ConfirmationStatus,
        by: ConfirmationPerson,
        error: ConfirmationError? = null,
    ) {
        if (!repository.answer(confirmation.orgId, confirmation.id, status, by, clock.now(), error)) {
            requirePending(read(confirmation))
            throw ConfirmationClosedException()
        }
    }

    private suspend fun finish(
        confirmation: Confirmation,
        step: ConfirmationStep?,
        by: ConfirmationPerson,
    ): Confirmation {
        waits.changed(confirmation.id)
        val answered = read(confirmation)
        step?.let { audit.record(answered, it, by.userId) }
        return answered
    }

    private suspend fun read(confirmation: Confirmation): Confirmation =
        checkNotNull(repository.get(confirmation.orgId, confirmation.id)) { "A confirmation is never deleted." }

    private fun requirePending(confirmation: Confirmation) {
        when (confirmation.statusAt(clock.now())) {
            ConfirmationStatus.PENDING -> Unit
            ConfirmationStatus.EXPIRED -> throw ConfirmationExpiredException()
            else -> throw ConfirmationClosedException()
        }
    }

    private fun GovernanceConfirmer.inOrganization(orgId: Uuid) =
        GovernanceConfirmer(
            principal.copy(namedOrg = orgId.toString()),
            OrgPrincipal(orgId, member.memberId, member.role),
        )
}
