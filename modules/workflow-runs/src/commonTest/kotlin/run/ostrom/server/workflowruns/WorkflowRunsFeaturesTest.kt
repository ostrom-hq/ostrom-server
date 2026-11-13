// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowRunsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("workflows.v1", "notifications.v1"), WorkflowRunsModule.features(koin))
    }
}
