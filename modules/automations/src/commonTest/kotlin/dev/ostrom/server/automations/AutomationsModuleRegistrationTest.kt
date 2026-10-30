// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.kernel.config.MapConfigSource
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("automations", AutomationsModule.name)
        koinApplication { modules(AutomationsModule.koinModule) }.close()
    }

    @Test
    fun webhookUrlsUseThePublicUrlWithoutItsTrailingSlash() {
        val configured = MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example/"))
        assertEquals("https://ostrom.example", publicUrlOf(configured))
        assertEquals("", publicUrlOf(MapConfigSource(emptyMap())))
    }
}
