// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.sessions.LaunchLabels
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import kotlin.uuid.Uuid

/**
 * [LaunchLabels] on the launches: the sessions module copies a launch's labels onto each segment it opens.
 *
 * @param launches The launches.
 */
class LaunchLabelsDirectory(private val launches: LaunchesRepository) : LaunchLabels {
    override suspend fun of(orgId: Uuid, launchId: String): Map<String, String> = launches.labelsOf(orgId, launchId)
}
