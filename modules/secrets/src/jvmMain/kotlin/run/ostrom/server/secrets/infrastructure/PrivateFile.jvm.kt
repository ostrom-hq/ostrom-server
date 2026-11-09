// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.infrastructure

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlin.uuid.Uuid

// The JVM target only runs the tests and Kover; the same behaviour as the native actual, with java.nio.

/** `CREATE_NEW` with mode 0600 for the temporary file, then a hard link that never replaces [path]. */
internal actual fun createOwnerOnlyFile(path: String, content: String): Boolean {
    val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    val temporary = Paths.get("$path.${Uuid.random()}.tmp")
    FileChannel.open(temporary, setOf(CREATE_NEW, WRITE), ownerOnly).use { channel ->
        val buffer = ByteBuffer.wrap(content.encodeToByteArray())
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    return try {
        Files.createLink(Paths.get(path), temporary)
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } finally {
        Files.delete(temporary)
    }
}
