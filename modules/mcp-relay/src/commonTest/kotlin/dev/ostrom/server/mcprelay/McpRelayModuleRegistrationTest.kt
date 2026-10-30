// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class McpRelayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("mcp-relay", McpRelayModule.name)
        koinApplication { modules(McpRelayModule.koinModule) }.close()
    }
}
