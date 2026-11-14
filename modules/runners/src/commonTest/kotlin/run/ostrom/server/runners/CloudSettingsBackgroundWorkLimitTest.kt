// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.CloudSettingsBackgroundWorkLimit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudSettingsBackgroundWorkLimitTest {
    @Test
    fun theCapIsTheOrganizationsCloudSettingOrTheDefaultWithoutTheCloudModule() = runTest {
        val settings = CloudSettingsSource { CloudSettings(backgroundWorkMaxMinutes = 30) }
        assertEquals(30, CloudSettingsBackgroundWorkLimit(settings).maxMinutes(ORG_A))
        assertEquals(240, CloudSettingsBackgroundWorkLimit(null).maxMinutes(ORG_A))
    }
}
