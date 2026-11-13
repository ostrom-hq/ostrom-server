// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.usecases

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.rules.domain.models.Decider
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionSubject
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.services.RuleDocuments
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Reads the organization's self-approval policy, the one its gates apply. */
interface GetGatePolicyUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @return The setting; [SelfApprovalPolicy.DEFAULT] when the organization never chose.
     */
    suspend operator fun invoke(orgId: Uuid): SelfApprovalPolicy
}

/**
 * [GetGatePolicyUseCase] on the repository.
 *
 * @param repository The settings.
 */
class GetGatePolicyUseCaseImpl(private val repository: RulesRepository) : GetGatePolicyUseCase {
    override suspend fun invoke(orgId: Uuid): SelfApprovalPolicy =
        repository.selfApproval(orgId) ?: SelfApprovalPolicy.DEFAULT
}

/** Sets the self-approval policy: a signed decision (APPROBATION, 28/10; prototype `PUT /api/bridge/policy`). */
interface SetGatePolicyUseCase {
    /**
     * Sets it. Setting what an earlier decision already put in force records nothing; the organization's first choice
     * is recorded, even when it is the default.
     *
     * @param decider Who decides.
     * @param policy The setting.
     * @return The setting in force.
     */
    suspend operator fun invoke(decider: Decider, policy: SelfApprovalPolicy): SelfApprovalPolicy
}

/**
 * [SetGatePolicyUseCase] on the repository, the setting locked for the change.
 *
 * @param repository The settings.
 * @param signDecisions Signs the decision once committed.
 * @param clock The time.
 */
class SetGatePolicyUseCaseImpl(
    private val repository: RulesRepository,
    private val signDecisions: SignDecisionsUseCase,
    private val clock: Clock,
) : SetGatePolicyUseCase {
    override suspend fun invoke(decider: Decider, policy: SelfApprovalPolicy): SelfApprovalPolicy {
        val now = clock.now()
        val decision = repository.transaction(decider.orgId) {
            val stored = lockSelfApproval()
            val before = stored ?: SelfApprovalPolicy.DEFAULT
            putSelfApproval(policy, now)
            // An organization's first choice is a decision even when it is the default: the audit shows who chose.
            if (stored != null && before == policy) {
                null
            } else {
                Decision(
                    orgId = decider.orgId,
                    id = "dec_${Uuid.random()}",
                    at = now,
                    by = decider.name,
                    subject = DecisionSubject(POLICY, SELF_APPROVAL, LABEL),
                    action = EDITED,
                    before = RuleDocuments.policy(before),
                    after = RuleDocuments.policy(policy),
                ).also { insertDecision(it) }
            }
        }
        signDecisions(listOfNotNull(decision))
        return policy
    }

    private companion object {
        const val POLICY = "policy"
        const val SELF_APPROVAL = "self-approval"
        const val LABEL = "Self-approval"
        const val EDITED = "edited"
    }
}
