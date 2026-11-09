// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("packages.v1", "workflows.v1"), CompilerModule.features(koin))
    }
}
