// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.usecases

import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.launches.domain.models.AgentVerdict
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.models.OtherDeveloperForbiddenException
import run.ostrom.server.launches.domain.models.UnknownDeveloperException
import run.ostrom.server.launches.domain.services.LauncherResolver

/**
 * May this developer launch this agent with this model (contract `checkAgentPolicy`, prototype `/api/bridge/policy`).
 * A developer reads their own verdict; an owner or an admin also reads another member's (`ostrom policy check --dev`),
 * to see what they will be told.
 */
interface CheckAgentPolicyUseCase {
    /**
     * Reads the organization's policy for a launch.
     *
     * @param launcher The person who asks, from their credential.
     * @param agent The agent's name in Ostrom, or null.
     * @param model The model, or null.
     * @param dev The login of the developer the verdict is for; null, or the caller's own login, for the caller.
     *
     * @return The verdict.
     *
     * @throws OtherDeveloperForbiddenException When [dev] is someone else and the caller is not an owner or an admin.
     * @throws UnknownDeveloperException When [dev] is not a member of the caller's organization.
     */
    suspend operator fun invoke(launcher: Launcher, agent: String?, model: String?, dev: String? = null): AgentVerdict
}

/**
 * [CheckAgentPolicyUseCase] on the [LaunchPolicy].
 *
 * @param policy The policy.
 * @param launchers Resolves the developer an admin names, in the admin's organization only.
 */
class CheckAgentPolicyUseCaseImpl(
    private val policy: LaunchPolicy,
    private val launchers: LauncherResolver,
) : CheckAgentPolicyUseCase {
    override suspend fun invoke(launcher: Launcher, agent: String?, model: String?, dev: String?): AgentVerdict {
        val refusal = policy.refusal(developer(launcher, dev), agent, model)
        return if (refusal == null) AgentVerdict(allowed = true) else AgentVerdict(allowed = false, reason = refusal)
    }

    // The role first: a member learns nothing of who is in the organization, not even whether a login is.
    private suspend fun developer(launcher: Launcher, dev: String?): Launcher = when {
        dev == null || dev.equals(launcher.login, ignoreCase = true) -> launcher
        launcher.role != OrgRole.OWNER && launcher.role != OrgRole.ADMIN -> throw OtherDeveloperForbiddenException()
        else -> launchers.colleague(launcher.orgId, dev) ?: throw UnknownDeveloperException()
    }
}
