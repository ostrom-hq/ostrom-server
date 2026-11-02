// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.usecases

import dev.ostrom.server.launches.domain.models.AgentVerdict
import dev.ostrom.server.launches.domain.models.Launcher

/** May this person launch this agent with this model (contract `checkAgentPolicy`, prototype `/api/bridge/policy`). */
interface CheckAgentPolicyUseCase {
    /**
     * Reads the organization's policy for a launch.
     *
     * @param launcher The person, from their credential: the `dev` of the query is never read.
     * @param agent The agent's name in Ostrom, or null.
     * @param model The model, or null.
     *
     * @return The verdict.
     */
    suspend operator fun invoke(launcher: Launcher, agent: String?, model: String?): AgentVerdict
}

/**
 * [CheckAgentPolicyUseCase] on the [LaunchPolicy].
 *
 * @param policy The policy.
 */
class CheckAgentPolicyUseCaseImpl(private val policy: LaunchPolicy) : CheckAgentPolicyUseCase {
    override suspend fun invoke(launcher: Launcher, agent: String?, model: String?): AgentVerdict {
        val refusal = policy.refusal(launcher, agent, model)
        return if (refusal == null) AgentVerdict(allowed = true) else AgentVerdict(allowed = false, reason = refusal)
    }
}
