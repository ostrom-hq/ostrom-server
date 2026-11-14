// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.server.admin.infrastructure.LegacyEnvironment
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LegacyEnvironmentTest {
    @Test
    fun aVariableIsReadFromItselfThenFromTheFileItsFileVariableNames() {
        val files = mapOf("/run/key" to " sk_test_file\n", "/run/empty" to " \n")
        val read = LegacyEnvironment.of(
            MapConfigSource(
                mapOf(
                    "OSTROM_A" to "direct",
                    "OSTROM_A_FILE" to "/run/key",
                    "OSTROM_B_FILE" to "/run/key",
                    "OSTROM_C_FILE" to "/run/empty",
                ),
            ),
        ) { files.getValue(it) }

        assertEquals("direct", read("OSTROM_A"), "The variable wins over its file")
        assertEquals("sk_test_file", read("OSTROM_B"))
        assertNull(read("OSTROM_C"), "An empty file is unset")
        assertNull(read("OSTROM_D"))
    }

    @Test
    fun theFileIsReadFromTheFilesystemAndAMissingOneStopsTheStart() {
        val file = Path(SystemTemporaryDirectory, "ostrom-legacy-${Uuid.random()}.key")
        SystemFileSystem.sink(file).buffered().use { it.writeString("whsec_disk\n") }
        try {
            val read = LegacyEnvironment.of(MapConfigSource(mapOf("OSTROM_X_FILE" to file.toString())))
            assertEquals("whsec_disk", read("OSTROM_X"))
        } finally {
            SystemFileSystem.delete(file)
        }
        val missing = LegacyEnvironment.of(MapConfigSource(mapOf("OSTROM_X_FILE" to "/nonexistent/ostrom.key")))
        val error = assertFailsWith<InvalidConfigException> { missing("OSTROM_X") }
        assertEquals("The file /nonexistent/ostrom.key does not exist.", error.message)
    }
}
