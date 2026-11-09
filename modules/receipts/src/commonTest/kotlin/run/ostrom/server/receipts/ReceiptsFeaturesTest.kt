// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("receipts.v1"), ReceiptsModule.features(koin))
    }
}
