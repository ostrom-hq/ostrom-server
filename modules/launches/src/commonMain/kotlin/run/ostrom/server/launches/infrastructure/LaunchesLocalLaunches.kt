// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.identity.domain.AgentSessionLiveUseCase
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.runners.domain.LocalLaunches
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [LocalLaunches] on this module's launches (TeamCode's `localRun` and its `running`): a launch is the person's own
 * while they started it, and still running until it ends ([run.ostrom.server.launches.domain.usecases.EndLaunchUseCase]
 * revokes its agent tokens) or expires.
 *
 * @param launches The launches.
 * @param live Whether one of a launch's agent tokens is still live.
 * @param clock The time.
 */
class LaunchesLocalLaunches(
    private val launches: LaunchesRepository,
    private val live: AgentSessionLiveUseCase,
    private val clock: Clock,
) : LocalLaunches {
    override suspend fun running(orgId: Uuid, userId: String, launchId: String): Boolean {
        val launch = launches.find(orgId, launchId)?.takeIf { it.userId.toString() == userId } ?: return false
        return launch.expiresAt > clock.now() && live(orgId, launchId)
    }
}
