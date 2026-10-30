// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithoutTables() {
        assertEquals("import", ImportModule.name)
        assertEquals(emptyList(), ImportModule.migrations)
        koinApplication { modules(ImportModule.koinModule) }.close()
    }
}
