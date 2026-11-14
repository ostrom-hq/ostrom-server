// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.infrastructure.CloudSettingsAutomationLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloudSettingsAutomationLimitsTest {
    private class Moving(var now: Instant = Instant.fromEpochSeconds(1_000)) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun theCapIsTheOrganizationsCloudSettingOrTwoWithoutTheCloudModule() = runTest {
        val settings = CloudSettingsSource { CloudSettings(maxRunningAutomationsPerMember = 7) }
        assertEquals(7, CloudSettingsAutomationLimits(settings).maxRunningPerMember(ORG_A))
        assertEquals(2, CloudSettingsAutomationLimits(null).maxRunningPerMember(ORG_A))
    }

    @Test
    fun anOrganizationsCapIsReadOnceEvery30SecondsAndNeverSharedWithAnother() = runTest {
        val caps = mutableMapOf(ORG_A to 3, ORG_B to 9)
        val reads = mutableListOf<Uuid>()
        val clock = Moving()
        val source = CloudSettingsSource { org ->
            reads += org
            CloudSettings(maxRunningAutomationsPerMember = caps.getValue(org))
        }
        val limits = CloudSettingsAutomationLimits(source, clock)
        assertEquals(3, limits.maxRunningPerMember(ORG_A))
        assertEquals(9, limits.maxRunningPerMember(ORG_B))
        caps[ORG_A] = 5
        clock.now += 29.seconds
        assertEquals(3, limits.maxRunningPerMember(ORG_A))
        assertEquals(listOf(ORG_A, ORG_B), reads)
        clock.now += 1.seconds
        assertEquals(5, limits.maxRunningPerMember(ORG_A))
        assertEquals(9, limits.maxRunningPerMember(ORG_B))
    }
}
