// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("reports.v1"), ReportsModule.features(koin))
    }
}
