// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.kernel.config.MapConfigSource
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
