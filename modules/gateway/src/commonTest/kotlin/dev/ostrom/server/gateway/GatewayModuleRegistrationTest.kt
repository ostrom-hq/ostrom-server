// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.GatewaySettingsRepository
import dev.ostrom.server.gateway.domain.PolicyModelAccess
import dev.ostrom.server.kernel.auth.ModelAccess
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
