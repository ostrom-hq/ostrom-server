// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The organizations' cloud settings the session reconciler reads. */
fun interface CloudSettingsSource {
    /**
     * The settings in force.
     *
     * @param orgId The organization.
     *
     * @return They; the defaults while nothing is stored.
     */
    suspend fun current(orgId: Uuid): CloudSettings
}

/** An organization's cloud settings, read by its members and saved by its owners and admins. */
interface CloudSettingsUseCase : CloudSettingsSource {
    /**
     * The settings.
     *
     * @param caller A member of the organization.
     *
     * @return They.
     */
    suspend fun get(caller: Caller): CloudSettings

    /**
     * Replaces the settings, with an optimistic `updatedAt` (TeamCode 3b716702).
     *
     * @param caller An owner or an admin.
     * @param settings The new settings.
     * @param expectedUpdatedAt The `updatedAt` of the settings edited, null while nothing was stored.
     *
     * @return The settings saved.
     *
     * @throws InvalidCloudSettingsException When a value is refused.
     * @throws CloudSettingsConflictException When another save came first.
     */
    suspend fun save(caller: Caller, settings: CloudSettings, expectedUpdatedAt: Instant?): CloudSettings
}

/**
 * [CloudSettingsUseCase].
 *
 * @param repository The stored settings.
 * @param clock The clock.
 */
class CloudSettingsUseCaseImpl(
    private val repository: CloudSettingsRepository,
    private val clock: Clock,
) : CloudSettingsUseCase {
    override suspend fun current(orgId: Uuid): CloudSettings = repository.get(orgId) ?: CloudSettings()

    override suspend fun get(caller: Caller): CloudSettings = current(caller.orgId)

    override suspend fun save(caller: Caller, settings: CloudSettings, expectedUpdatedAt: Instant?): CloudSettings {
        caller.requireAdmin()
        settingsProblem(settings)?.let { throw InvalidCloudSettingsException(it) }
        val next = settings.copy(updatedAt = clock.nowMillis(), updatedBy = caller.memberId)
        if (!repository.save(caller.orgId, next, expectedUpdatedAt)) throw CloudSettingsConflictException()
        return next
    }
}
