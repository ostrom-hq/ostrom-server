// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * The capacity controller's settings of an org (TeamCode's `PacingSettings`, `capacity.pacing.v1`). A provider window
 * counts as exhausted at its account's `utilizationCeiling`, so Ostrom has no org-wide pressure threshold.
 *
 * @property observationOnly The controller keeps forecasting but neither spaces admissions nor delivers its advice.
 * @property autoPoolSize The org's daily budget is sized from its shared accounts instead of the typed one.
 */
data class PacingSettings(val observationOnly: Boolean = false, val autoPoolSize: Boolean = false)

/** The org-wide controller settings, shared by every replica (TeamCode's `PacingSettingsStore`). */
interface PacingSettingsRepository {
    /** The org's settings, the defaults when none was stored. */
    suspend fun get(orgId: Uuid): PacingSettings

    /**
     * Merges the fields given (null keeps the stored value) under the org's lock, so two administrators never
     * overwrite each other.
     *
     * @return The settings before and after.
     */
    suspend fun update(
        orgId: Uuid,
        observationOnly: Boolean?,
        autoPoolSize: Boolean?,
    ): Pair<PacingSettings, PacingSettings>
}

/** Reads the settings (`getPacingSettings`): any member. */
interface GetPacingSettingsUseCase {
    /** The settings of the caller's org. */
    suspend operator fun invoke(caller: Caller): PacingSettings
}

/** [GetPacingSettingsUseCase] on the repository. */
class GetPacingSettingsUseCaseImpl(private val settings: PacingSettingsRepository) : GetPacingSettingsUseCase {
    override suspend fun invoke(caller: Caller): PacingSettings = settings.get(caller.orgId)
}

/** Changes the settings (`setPacingSettings`): owners and admins. */
interface SetPacingSettingsUseCase {
    /**
     * Merges the fields given.
     *
     * @return The settings before and after, for the audit log.
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException For a member who does not administer the org.
     */
    suspend operator fun invoke(
        caller: Caller,
        observationOnly: Boolean?,
        autoPoolSize: Boolean?,
    ): Pair<PacingSettings, PacingSettings>
}

/** [SetPacingSettingsUseCase] on the repository. */
class SetPacingSettingsUseCaseImpl(private val settings: PacingSettingsRepository) : SetPacingSettingsUseCase {
    override suspend fun invoke(
        caller: Caller,
        observationOnly: Boolean?,
        autoPoolSize: Boolean?,
    ): Pair<PacingSettings, PacingSettings> {
        caller.requireAdmin()
        return settings.update(caller.orgId, observationOnly, autoPoolSize)
    }
}

/**
 * The audit events of a change (TeamCode's names): `pacing.observation_enabled` / `pacing.enforcement_enabled`, and
 * `pool_size.automatic_enabled` / `pool_size.manual_enabled`; none for a field left as it was.
 */
fun pacingAuditEvents(before: PacingSettings, after: PacingSettings): List<String> = buildList {
    if (before.observationOnly != after.observationOnly) {
        add(if (after.observationOnly) "pacing.observation_enabled" else "pacing.enforcement_enabled")
    }
    if (before.autoPoolSize != after.autoPoolSize) {
        add(if (after.autoPoolSize) "pool_size.automatic_enabled" else "pool_size.manual_enabled")
    }
}
