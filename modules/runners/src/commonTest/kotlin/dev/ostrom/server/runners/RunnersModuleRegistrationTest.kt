package dev.ostrom.server.runners

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnersModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("runners", RunnersModule.name)
        koinApplication { modules(RunnersModule.koinModule) }.close()
    }
}
