package dev.ostrom.server.identity

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("identity", IdentityModule.name)
        koinApplication { modules(IdentityModule.koinModule) }.close()
    }
}
