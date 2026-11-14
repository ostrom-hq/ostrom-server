// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import kotlin.test.Test
import kotlin.test.assertEquals

// TeamCode 3da27ca0: no two migrations share a version. Each module's history is its own table (`flyway_<module>`),
// so a version is unique within a module, the modules' names unique, and versions run from 1 without a gap.
class MigrationVersionsTest {
    @Test
    fun everyModuleNumbersItsMigrationsFromOneWithoutAGapOrARepeat() {
        val modules = serverModules()
        val histories = modules.map { it.name.replace('-', '_') }.toSet()
        assertEquals(modules.size, histories.size, "Two modules share a history")
        for (module in modules) {
            val versions = module.migrations.map { it.version }
            assertEquals((1L..versions.size).toList(), versions, "The versions of module ${module.name}")
        }
    }
}
