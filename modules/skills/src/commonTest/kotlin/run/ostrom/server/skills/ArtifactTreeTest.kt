// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.ArtifactTooLargeException
import run.ostrom.server.skills.domain.ArtifactTree
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.InvalidCatalogRequestException
import run.ostrom.server.skills.infrastructure.OpenSslSha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactTreeTest {
    private val sha256 = OpenSslSha256()

    private fun file(path: String, content: String = "x", mode: FileMode = FileMode.FILE) =
        ArtifactFile(path, mode, content.encodeToByteArray())

    private fun type(block: () -> Unit): String? = assertFailsWith<OstromException> { block() }.type

    @Test
    fun namesAreLowercaseWordsJoinedBySingleHyphens() {
        ArtifactTree.checkName("code-review-2", "skill")
        assertEquals("invalid_skill_name", type { ArtifactTree.checkName("Code", "skill") })
        assertEquals("invalid_hook_name", type { ArtifactTree.checkName("a--b", "hook") })
        assertEquals("invalid_skill_name", type { ArtifactTree.checkName("a".repeat(65), "skill") })
        ArtifactTree.checkName("a".repeat(64), "skill")
    }

    @Test
    fun versionsAndDigestsHaveTheirShapes() {
        ArtifactTree.checkVersion("1.0.0-rc.1+build_7", "skill")
        assertEquals("invalid_skill_version", type { ArtifactTree.checkVersion(".1", "skill") })
        assertEquals("invalid_skill_version", type { ArtifactTree.checkVersion("1".repeat(65), "skill") })
        ArtifactTree.checkDigest("sha256:" + "a".repeat(64))
        assertEquals("invalid_skill_installation", type { ArtifactTree.checkDigest("sha256:" + "A".repeat(64)) })
    }

    @Test
    fun pathsAreRelativePortableAndBounded() {
        ArtifactTree.checkPath("SKILL.md", "skill")
        ArtifactTree.checkPath("a/b/c/d/e/f/g/h.txt", "skill")
        listOf(
            "", "/abs", "a//b", "../x", ".hidden", "a/./b", "dir/", "x.", "con", "CON.txt", "lpt9.log", "a b",
            "a/b/c/d/e/f/g/h/i", "a".repeat(256), "a\\b",
        ).forEach { path -> assertEquals("invalid_skill_path", type { ArtifactTree.checkPath(path, "skill") }, path) }
    }

    @Test
    fun anArtifactHoldsUniqueBoundedFiles() = runTest {
        suspend fun entries(vararg files: ArtifactFile, max: Int = 3, bytes: Long = 10) =
            ArtifactTree.entries(files.toList(), max, bytes, "skill", sha256)

        assertEquals("invalid_skill_artifact", assertFailsWith<InvalidCatalogRequestException> { entries() }.type)
        assertFailsWith<InvalidCatalogRequestException> { entries(file("a"), file("b"), file("c"), file("d")) }
        assertEquals("invalid_skill_path", assertFailsWith<InvalidCatalogRequestException> { entries(file("..")) }.type)
        val large = assertFailsWith<ArtifactTooLargeException> { entries(file("a", "123456"), file("b", "12345")) }
        assertEquals("skill_artifact_too_large", large.type)
        assertFailsWith<InvalidCatalogRequestException> { entries(file("readme.md"), file("README.md")) }
        assertFailsWith<InvalidCatalogRequestException> { entries(file("Docs"), file("docs/a.md")) }
        assertFailsWith<InvalidCatalogRequestException> { entries(file("a/b"), file("a/b/c")) }

        val sorted = entries(file("b/x.sh", mode = FileMode.EXECUTABLE), file("a.md"), file("b/y.md"))
        assertEquals(listOf("a.md", "b/x.sh", "b/y.md"), sorted.map { it.path })
        assertEquals(FileMode.EXECUTABLE, sorted[1].mode)
        assertEquals(1, sorted[0].size)
    }

    @Test
    fun theDigestIsTeamCodesCanonicalOne() = runTest {
        // sha256 over `<path>\0<mode>\0<size>\0<sha256 hex>\n` per file sorted by path (the CLI checks the same).
        val one = ArtifactTree.recompute(listOf(file("SKILL.md", "# Skill")), sha256)
        assertEquals("e2151f8490121dc5e6fd36c1d4e00b6da5593595e3eb8ece76c1d0ec3f310979", one.single().sha256)
        assertEquals(
            "sha256:fed02854d013630baa3518562a6b42207967f7c141b6043ff847366365c20588",
            ArtifactTree.digest(one, sha256),
        )
        val two = ArtifactTree.recompute(
            listOf(file("bin/run", "#!/bin/sh\n", FileMode.EXECUTABLE), file("SKILL.md", "# Skill")),
            sha256,
        )
        assertEquals(
            "sha256:c762fe36ae784b01da5ff9f1768096d76b08088620d5aa02230dc713a2f87166",
            ArtifactTree.digest(two, sha256),
        )
        assertEquals(
            "SKILL.md\u0000file\u00007\u0000${one.single().sha256}\n",
            ArtifactTree.fileLines(one),
        )
    }
}
