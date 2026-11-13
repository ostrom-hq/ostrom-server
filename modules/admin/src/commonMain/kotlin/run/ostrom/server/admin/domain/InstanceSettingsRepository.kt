// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.admin.domain

/** The stored settings sections. */
interface InstanceSettingsRepository {
    /**
     * Reads a section.
     *
     * @param section The section's name.
     *
     * @return It, or null when nothing was ever stored.
     */
    suspend fun get(section: String): StoredSettings?

    /**
     * Reads every stored section.
     *
     * @return They, by name.
     */
    suspend fun all(): Map<String, StoredSettings>

    /**
     * Saves a section over the version it replaces.
     *
     * @param settings The new settings, their version the replaced one plus one.
     *
     * @return False when the stored version is no longer `settings.version - 1` (another save came first).
     */
    suspend fun save(settings: StoredSettings): Boolean

    /**
     * Stores a section's first settings, unless any were stored (another replica's import, or an administrator's
     * save, came first).
     *
     * @param settings The settings, version 1.
     *
     * @return True when stored.
     */
    suspend fun insertIfAbsent(settings: StoredSettings): Boolean
}
