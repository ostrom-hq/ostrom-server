// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.CloudRuntimeAutomationLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudRuntimeAutomationLimitsTest {
    @Test
    fun theCapIsTheCloudRuntimesAndTwoWithoutOne() = runTest {
        val set = object : CloudRuntimeSource {
            override suspend fun current(): CloudRuntime = CloudRuntime(maxRunningAutomationsPerMember = 7)
        }
        assertEquals(7, CloudRuntimeAutomationLimits(set).maxRunningPerMember(ORG_A))
        assertEquals(2, CloudRuntimeAutomationLimits(null).maxRunningPerMember(ORG_A))
    }
}
