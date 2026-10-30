// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.platform

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

// The JVM target only runs the tests and Kover; the same behaviour as the native actuals, with java.nio.

/** Reads the variable with `System.getenv`. */
actual fun getEnv(name: String): String? = System.getenv(name)

internal actual fun createPrivateFile(path: String, content: String): Boolean {
    val target = Paths.get(path)
    val temporary = Paths.get("$path.${UUID.randomUUID()}.tmp")
    val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    FileChannel.open(temporary, setOf(CREATE_NEW, WRITE), ownerOnly).use { channel ->
        val buffer = ByteBuffer.wrap(content.encodeToByteArray())
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    return try {
        Files.createLink(target, temporary)
        true
    } catch (expected: FileAlreadyExistsException) {
        false
    } finally {
        Files.delete(temporary)
    }
}
