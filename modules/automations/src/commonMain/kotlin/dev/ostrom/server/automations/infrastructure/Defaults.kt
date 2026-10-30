// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.infrastructure

import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.automations.domain.LaunchRequest
import dev.ostrom.server.automations.domain.LaunchResult
import dev.ostrom.server.automations.domain.MemberDirectory
import dev.ostrom.server.automations.domain.Wait
import dev.ostrom.server.automations.domain.WaitEvents
import kotlin.uuid.Uuid

/**
 * The member directory used until the module owning the members binds one: no one but the asker can be asked, so a
 * wait is never assigned to an id that may not be a member.
 */
internal object NoOtherMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}

/** No one listens to waits until notifications and the waking of sessions bind a [WaitEvents]. */
internal object SilentWaitEvents : WaitEvents {
    override suspend fun created(wait: Wait) = Unit

    override suspend fun resolved(wait: Wait) = Unit

    override suspend fun cancelled(wait: Wait) = Unit
}

/** The launcher until a module serving cloud sessions binds one: every run fails `cloud_sessions_unavailable`. */
internal object UnavailableLauncher : AutomationLauncher {
    override suspend fun launch(request: LaunchRequest): LaunchResult =
        LaunchResult.Refused("cloud_sessions_unavailable")

    override suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean = false
}
