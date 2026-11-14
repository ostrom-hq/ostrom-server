// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsConflictException
import run.ostrom.server.cloud.domain.CloudSettingsRepository
import run.ostrom.server.cloud.domain.CloudSettingsUseCaseImpl
import run.ostrom.server.cloud.domain.InvalidCloudSettingsException
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloudSettingsTest {
    /** The settings in memory, per organization. */
    private class MemorySettings : CloudSettingsRepository {
        val rows = mutableMapOf<Uuid, CloudSettings>()

        override suspend fun get(orgId: Uuid): CloudSettings? = rows[orgId]

        override suspend fun save(orgId: Uuid, settings: CloudSettings, expectedUpdatedAt: Instant?): Boolean {
            if (rows[orgId]?.updatedAt != expectedUpdatedAt) return false
            rows[orgId] = settings
            return true
        }
    }

    private val repository = MemorySettings()
    private val clock = SettableClock()
    private val settings = CloudSettingsUseCaseImpl(repository, clock)
    private val admin = Caller(ORG_A, ALICE, MemberRole.ADMIN)
    private val member = Caller(ORG_A, BOB, MemberRole.MEMBER)

    @Test
    fun theDefaultsApplyUntilAnAdminSavesThenEachOrganizationHasItsOwn() = runTest {
        assertEquals(CloudSettings(), settings.get(member))
        assertEquals(240, settings.current(ORG_A).backgroundWorkMaxMinutes)
        assertNull(settings.current(ORG_A).updatedAt)

        val saved = settings.save(admin, CloudSettings(backgroundWorkMaxMinutes = 60), null)
        assertEquals(CloudSettings(60, NOW, ALICE), saved)
        assertEquals(saved, settings.get(member))
        assertEquals(CloudSettings(), settings.current(ORG_B))
        assertEquals(0, settings.save(admin, CloudSettings(0), saved.updatedAt).backgroundWorkMaxMinutes)
    }

    @Test
    fun onlyAnAdminSavesAValueInRangeOverTheSettingsItRead() = runTest {
        assertFailsWith<AdminRequiredException> { settings.save(member, CloudSettings(60), null) }
        assertFailsWith<InvalidCloudSettingsException> { settings.save(admin, CloudSettings(-1), null) }
        assertFailsWith<InvalidCloudSettingsException> { settings.save(admin, CloudSettings(1441), null) }
        assertEquals(1440, settings.save(admin, CloudSettings(1440), null).backgroundWorkMaxMinutes)
        assertFailsWith<CloudSettingsConflictException> { settings.save(admin, CloudSettings(30), null) }
    }
}
