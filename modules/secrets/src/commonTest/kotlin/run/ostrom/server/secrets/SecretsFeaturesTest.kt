// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("secrets.v1"), SecretsModule.features(koin))
    }
}
