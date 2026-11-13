// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("audit.v1", "servicekeys.scopes.v1"), IdentityModule.features(koin))
    }
}
