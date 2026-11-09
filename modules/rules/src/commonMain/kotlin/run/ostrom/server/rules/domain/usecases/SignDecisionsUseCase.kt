// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.usecases

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.services.DecisionSigner
import kotlinx.coroutines.CancellationException

/**
 * Signs the receipts of committed decisions that have none yet. A decision is committed before its receipt is signed,
 * so signing never holds a rule's lock nor a second connection; when signing fails, the next read of the decisions
 * signs it (as the gates do).
 */
interface SignDecisionsUseCase {
    /**
     * Signs those not signed yet.
     *
     * @param decisions The decisions.
     * @return Them, marked signed where signing worked.
     */
    suspend operator fun invoke(decisions: List<Decision>): List<Decision>
}

/**
 * [SignDecisionsUseCase] with the receipts module's signer.
 *
 * @param repository Records which are signed.
 * @param signer Signs (idempotent for one decision).
 * @param logger Logs signing failures.
 */
class SignDecisionsUseCaseImpl(
    private val repository: RulesRepository,
    private val signer: DecisionSigner,
    private val logger: Logger,
) : SignDecisionsUseCase {
    override suspend fun invoke(decisions: List<Decision>): List<Decision> {
        val signed = decisions.filter { !it.signed && sign(it) }.map { it.id }.toSet()
        if (signed.isEmpty()) return decisions
        // All in one organization: those of one page, or the one decision of a change.
        repository.markSigned(decisions.first().orgId, signed.toList())
        return decisions.map { if (it.id in signed) it.copy(signed = true) else it }
    }

    private suspend fun sign(decision: Decision): Boolean = try {
        signer.sign(decision)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("decision receipt signing failed", mapOf("decision_id" to decision.id), error)
        false
    }
}
