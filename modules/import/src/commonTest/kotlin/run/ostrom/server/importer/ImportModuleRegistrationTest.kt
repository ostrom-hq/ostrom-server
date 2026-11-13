// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsProvenanceTable() {
        assertEquals("import", ImportModule.name)
        assertEquals(1, ImportModule.migrations.size, "Its provenance table")
        koinApplication { modules(ImportModule.koinModule) }.close()
    }
}
