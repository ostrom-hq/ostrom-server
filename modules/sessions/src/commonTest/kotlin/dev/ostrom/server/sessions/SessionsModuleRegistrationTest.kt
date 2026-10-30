package dev.ostrom.server.sessions

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("sessions", SessionsModule.name)
        koinApplication { modules(SessionsModule.koinModule) }.close()
    }
}
