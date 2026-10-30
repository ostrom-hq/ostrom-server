// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gateway", GatewayModule.name)
        koinApplication { modules(GatewayModule.koinModule) }.close()
    }
}
