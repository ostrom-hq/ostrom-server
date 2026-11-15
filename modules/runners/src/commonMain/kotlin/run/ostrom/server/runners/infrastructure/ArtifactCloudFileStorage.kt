// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.runners.domain.CloudFileStorage
import kotlin.uuid.Uuid

/**
 * [CloudFileStorage] on the artifacts module's bucket (TeamCode's `artifact_storage`): files are available only while
 * the organization's stored bucket passed a connection test at its current version; any stored bucket still serves
 * the removal of files sent before.
 *
 * @param storage The organization's bucket, its secret sealed.
 */
internal class ArtifactCloudFileStorage(private val storage: ArtifactStorageRepository) : CloudFileStorage {
    override suspend fun active(orgId: Uuid): ArtifactStorageConnection? {
        val tested = storage.overview(orgId).storage
        if (tested?.verifiedAt == null) return null
        return storage.connection(orgId)?.takeIf { it.version == tested.version }
    }

    override suspend fun any(orgId: Uuid): ArtifactStorageConnection? = storage.connection(orgId)
}
