package dev.ostrom.server.gates

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gates", GatesModule.name)
        koinApplication { modules(GatesModule.koinModule) }.close()
    }
}
