// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.importer.domain.TeamCodeArtifactFile
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamCodeCatalogTest {
    @Test
    fun aFileNeverPrintsItsContent() {
        val file = TeamCodeArtifactFile("SKILL.md", "file", "secret-ish body".encodeToByteArray())
        assertEquals("TeamCodeArtifactFile(path=SKILL.md, mode=file, size=15)", "$file")
    }
}
