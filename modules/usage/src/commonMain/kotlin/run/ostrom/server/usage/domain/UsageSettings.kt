// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The usage ledger's settings (Admin → Settings → usage), as TeamCode keeps its retention in its dashboard
 * settings: how many days the ledger keeps its events. Read at each hourly purge; `OSTROM_USAGE_RETENTION_DAYS` is
 * imported once.
 */
object UsageSettings {
    /** Days the ledger keeps its events, 1 to [MAX_RETENTION_DAYS]. */
    const val RETENTION_DAYS: String = "retentionDays"

    /** Days kept by default: a full year plus a month, so that year-over-year totals stay possible. */
    const val DEFAULT_RETENTION_DAYS: Int = 400

    /** The longest retention accepted: ten years. */
    const val MAX_RETENTION_DAYS: Int = 3650

    /** The section the admin module serves and stores. */
    val SECTION: SettingsSection = SettingsSection(
        "usage",
        listOf(SettingsField(RETENTION_DAYS, legacyVariable = "OSTROM_USAGE_RETENTION_DAYS", check = ::checkDays)),
    )

    /**
     * The retention the instance's settings hold.
     *
     * @param settings The instance's settings.
     *
     * @return The stored retention, or [DEFAULT_RETENTION_DAYS] when none (or one the console would refuse) is stored.
     */
    suspend fun retention(settings: InstanceSettings): Duration =
        (days(settings.read(SECTION.name)[RETENTION_DAYS]) ?: DEFAULT_RETENTION_DAYS).days

    private fun days(value: String?): Int? = value?.toIntOrNull()?.takeIf { it >= 1 && it <= MAX_RETENTION_DAYS }

    private fun checkDays(value: String): String? =
        if (days(value) == null) "The retention is a whole number of days, 1 to $MAX_RETENTION_DAYS." else null
}
