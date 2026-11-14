// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.repositories

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How each organization compiles its packages (`GET /api/v1/packages/settings`). */
interface CompilerSettingsRepository {
    /**
     * Reads the settings.
     *
     * @param orgId The organization.
     * @return What is stored, or null when the organization never saved any (the defaults).
     */
    suspend fun read(orgId: Uuid): StoredCompilerSettings?

    /**
     * Stores the settings when the stored ones are still those the editor read.
     *
     * @param orgId The organization.
     * @param settings The settings.
     * @param by Who saves them (a login).
     * @param at When.
     * @param expected The `updatedAt` the editor read, null when they read none stored.
     * @return What is stored now, or null when another save came first.
     */
    suspend fun save(
        orgId: Uuid,
        settings: CompilerSettings,
        by: String,
        at: Instant,
        expected: Instant?,
    ): StoredCompilerSettings?
}
