package dev.ostrom.server.receipts

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("receipts", ReceiptsModule.name)
        koinApplication { modules(ReceiptsModule.koinModule) }.close()
    }
}
