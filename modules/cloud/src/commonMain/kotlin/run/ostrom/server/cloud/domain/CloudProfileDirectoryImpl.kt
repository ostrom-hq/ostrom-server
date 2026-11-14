// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import kotlin.uuid.Uuid

/**
 * [CloudProfileDirectory] on the organizations' profiles: what an automation's action or a workflow's agent node may
 * name, at their current version.
 *
 * @param repository The profiles.
 */
class CloudProfileDirectoryImpl(private val repository: CloudProfilesRepository) : CloudProfileDirectory {
    override suspend fun profiles(orgId: Uuid): List<NamedCloudProfile> =
        repository.list(orgId).map { NamedCloudProfile(it.id, it.name) }
}
