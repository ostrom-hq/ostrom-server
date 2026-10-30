// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsProvenanceTable() {
        assertEquals("import", ImportModule.name)
        assertEquals(listOf(1), ImportModule.migrations.map { it.version })
        koinApplication { modules(ImportModule.koinModule) }.close()
    }
}
