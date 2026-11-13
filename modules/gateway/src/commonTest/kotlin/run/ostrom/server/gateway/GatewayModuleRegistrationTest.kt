// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.PolicyModelAccess
import run.ostrom.server.kernel.auth.ModelAccess
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatewayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gateway", GatewayModule.name)
        koinApplication { modules(GatewayModule.koinModule) }.close()
    }

    @Test
    fun itBindsTheModelAccessALaunchReads() {
        val settings = module { single<GatewaySettingsRepository> { InMemorySettings() } }
        val app = koinApplication { modules(GatewayModule.koinModule, settings) }
        assertIs<PolicyModelAccess>(app.koin.get<ModelAccess>())
        app.close()
    }
}
