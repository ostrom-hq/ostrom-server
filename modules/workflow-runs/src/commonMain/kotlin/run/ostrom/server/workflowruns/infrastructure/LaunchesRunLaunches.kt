// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.workflowruns.domain.services.RunLaunches
import kotlin.uuid.Uuid

/**
 * [RunLaunches] on the launches module's launches.
 *
 * @param launches The launches.
 */
class LaunchesRunLaunches(private val launches: LaunchesRepository) : RunLaunches {
    override suspend fun cloudSession(orgId: Uuid, launchId: String): Uuid? =
        launches.find(orgId, launchId)?.cloudSessionId

    override suspend fun repo(orgId: Uuid, launchId: String): String? = launches.find(orgId, launchId)?.repo
}
