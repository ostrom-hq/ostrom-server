// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The per-key directories, through `java.nio`. A removal never follows a link, so a package cannot reach another key,
 * and what cannot be removed (a directory its package made unreadable) is left for the next sweep.
 */
class JvmHostFiles : HostFiles {
    override suspend fun createDirectories(path: String) {
        Files.createDirectories(Path.of(path))
    }

    override fun deleteRecursively(path: String) {
        val root = Path.of(path)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes) = remove(file)

                override fun visitFileFailed(file: Path, exc: IOException) = remove(file)

                override fun postVisitDirectory(dir: Path, exc: IOException?) = remove(dir)
            },
        )
    }

    private fun remove(path: Path): FileVisitResult {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Left in place: the next retain sweep tries again.
        }
        return FileVisitResult.CONTINUE
    }

    override fun list(path: String): List<String> {
        val directory = Path.of(path)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.toList() }
    }
}
