// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.infrastructure.readTextFileOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextFilesTest {
    @Test
    fun aFileIsReadWholeAndAMissingOneIsNone() {
        val path = Path(SystemTemporaryDirectory, "ostrom-cloud-token-test")
        SystemFileSystem.sink(path).buffered().use { it.writeString("sa-token\n") }
        assertEquals("sa-token\n", readTextFileOrNull(path.toString()))
        SystemFileSystem.delete(path)
        assertNull(readTextFileOrNull(path.toString()))
    }
}
