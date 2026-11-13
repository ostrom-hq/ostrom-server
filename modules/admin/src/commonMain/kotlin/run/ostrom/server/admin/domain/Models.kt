// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How a person became an administrator of the instance.
 *
 * @property wire The contract's name.
 */
enum class AdminSource(val wire: String) {
    /** They used the instance's setup link (`ostrom-server bootstrap`). */
    SETUP("setup"),

    /** Another administrator added them in the console. */
    DASHBOARD("dashboard"),

    /** Imported once from the former `OSTROM_INSTANCE_ADMINS`. */
    ENVIRONMENT("environment"),
    ;

    /** Reading a stored source. */
    companion object {
        /**
         * The source of a stored name.
         *
         * @param wire The name.
         *
         * @return The source.
         */
        fun of(wire: String): AdminSource = entries.first { it.wire == wire }
    }
}

/**
 * An administrator of the instance.
 *
 * @property userId The person's account.
 * @property source How they became one.
 * @property addedAt When.
 * @property addedBy Who added them, for [AdminSource.DASHBOARD].
 */
data class InstanceAdmin(
    val userId: Uuid,
    val source: AdminSource,
    val addedAt: Instant,
    val addedBy: Uuid? = null,
)

/**
 * A settings section as stored: plain values, and the secrets sealed (the envelopes, never opened here).
 *
 * @property section The section's name.
 * @property values The plain fields set, by name.
 * @property sealed The secret fields set, by name: each an envelope of the secrets key, bound to its section and field.
 * @property version The compare-and-set version, from 1.
 * @property updatedAt When it was saved last.
 * @property updatedBy The administrator who saved last; null when imported from the environment.
 */
data class StoredSettings(
    val section: String,
    val values: Map<String, String>,
    val sealed: Map<String, String>,
    val version: Int,
    val updatedAt: Instant,
    val updatedBy: Uuid?,
) {
    /** Whether these are the settings imported once from the environment, not yet saved by an administrator. */
    val importedFromEnvironment: Boolean get() = updatedBy == null

    override fun toString(): String =
        "StoredSettings(section=$section, values=${values.keys.sorted()}, sealed=${sealed.keys.sorted()}, " +
            "version=$version)"
}

/** The context a secret field is sealed with: an envelope copied to another section or field does not open. */
fun settingContext(section: String, field: String): String = "instance-setting:$section:$field"

/** Now, to the millisecond PostgreSQL keeps, so what a save answers is what a read returns. */
internal fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())
