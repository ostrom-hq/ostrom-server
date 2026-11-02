// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.kernel.auth.ModelAccess
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.services.LaunchModelRules
import dev.ostrom.server.launches.domain.services.LauncherResolver

/**
 * [LaunchModelRules] on the inference gateway's model policy ([ModelAccess]): the member's own list when the policy
 * has one, the organization's otherwise, matched by prefix as the gateway matches a call. The person's membership is
 * read as it is now: someone who left the organization is refused.
 *
 * @param access The gateway's model policy.
 * @param launchers The person's membership.
 */
class ModelAccessLaunchRules(
    private val access: ModelAccess,
    private val launchers: LauncherResolver,
) : LaunchModelRules {
    override suspend fun refusal(launcher: Launcher, model: String): String? {
        val member = launchers.member(launcher.orgId, launcher.userId) ?: return NOT_A_MEMBER
        return access.refusal(launcher.orgId, member.memberId, model)
    }

    /** The refusal of someone who is no longer a member. */
    companion object {
        /** Why a person who left the organization launches nothing. */
        const val NOT_A_MEMBER: String = "You are no longer a member of this organization."
    }
}
