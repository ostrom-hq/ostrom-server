// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The [InstanceSettings] the modules read: each section read from PostgreSQL at most every [ttl] per replica, its
 * secrets opened with the secrets key. A save on this replica is seen at once ([forget]), on the others within [ttl].
 * A secret that no longer opens (another key, a tampered row) is left out and said once per read, never its value.
 *
 * @param repository The stored sections.
 * @param cipher The secrets key.
 * @param logger Where an unopenable secret is said.
 * @param clock The clock.
 * @param ttl How long a section read is kept.
 */
class InstanceSettingsReader(
    private val repository: InstanceSettingsRepository,
    private val cipher: SecretCipher,
    private val logger: Logger,
    private val clock: Clock,
    private val ttl: Duration = 30.seconds,
) : InstanceSettings {
    // Replaced whole, never changed in place: a reader sees the old map or the new one.
    @Volatile
    private var held: Map<String, Pair<Instant, SettingsValues>> = emptyMap()

    // Counts the saves of this replica: a read that began before one never keeps what it read.
    @Volatile
    private var saves = 0L

    override suspend fun read(section: String): SettingsValues {
        val now = clock.now()
        held[section]?.takeIf { now - it.first < ttl }?.let { return it.second }
        val before = saves
        val values = repository.get(section)?.let { open(it) } ?: SettingsValues.EMPTY
        if (saves == before) held = held + (section to (now to values))
        return values
    }

    /**
     * Forgets a section, so the next read is the stored one.
     *
     * @param section The section.
     */
    fun forget(section: String) {
        saves++
        held = held - section
    }

    private suspend fun open(stored: StoredSettings): SettingsValues {
        val opened = stored.sealed.mapNotNull { (field, envelope) ->
            cipher.open(envelope, settingContext(stored.section, field))?.let { field to it.decodeToString() }
        }.toMap()
        val lost = stored.sealed.keys - opened.keys
        if (lost.isNotEmpty()) {
            logger.warn(
                "Some instance settings do not open with this secrets key: save them again in the console.",
                mapOf("section" to stored.section, "fields" to lost.sorted().joinToString(",")),
            )
        }
        return SettingsValues(stored.values + opened)
    }
}
