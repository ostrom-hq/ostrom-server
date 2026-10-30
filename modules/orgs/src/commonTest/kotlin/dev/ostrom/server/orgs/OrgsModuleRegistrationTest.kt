package dev.ostrom.server.orgs

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class OrgsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("orgs", OrgsModule.name)
        koinApplication { modules(OrgsModule.koinModule) }.close()
    }
}
