// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain.storage

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The organization's bucket (its secret sealed) and artifact settings, one row each, isolated by organization. */
interface ArtifactStorageRepository {
    /**
     * Reads the bucket, the settings and whether any artifact exists, in one transaction.
     *
     * @return The overview; default settings before the first save.
     */
    suspend fun overview(orgId: Uuid): ArtifactStorageOverview

    /**
     * Saves the bucket if [expectedVersion] is still the current one (0 when none is stored), sealing a new secret
     * or keeping the stored one, and clears its test.
     *
     * @param input What is saved; a null secret keeps the stored one.
     *
     * @return The saved bucket, or null when another save came first.
     */
    suspend fun saveStorage(
        orgId: Uuid,
        expectedVersion: Int,
        input: ArtifactStorageInput,
        now: Instant,
    ): ArtifactStorage?

    /**
     * Removes the bucket and its secret, and turns artifacts off.
     *
     * @return True when one was stored.
     */
    suspend fun deleteStorage(orgId: Uuid, now: Instant): Boolean

    /**
     * Records a connection test of bucket [version] while it is still the current one: a pass sets its `verifiedAt`,
     * a failure clears it.
     *
     * @return False, writing nothing, when the bucket changed or was removed meanwhile.
     */
    suspend fun recordTest(orgId: Uuid, version: Int, passed: Boolean, now: Instant): Boolean

    /**
     * Saves the settings if their `version` is still the current one.
     *
     * @return The saved settings, or null when another save came first.
     */
    suspend fun saveSettings(orgId: Uuid, settings: ArtifactSettings, now: Instant): ArtifactSettings?

    /**
     * Opens the bucket's connection, its secret decrypted, for the server's own use.
     *
     * @return The connection, or null when no bucket is stored or its secret no longer opens.
     */
    suspend fun connection(orgId: Uuid): ArtifactStorageConnection?
}
