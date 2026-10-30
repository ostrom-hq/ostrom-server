package dev.ostrom.server.secrets.infrastructure

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

// The JVM target only runs the tests and Kover; the same behaviour as the native actual, with java.nio.

/** `CREATE_NEW` with mode 0600. */
internal actual fun createOwnerOnlyFile(path: String, content: String): Boolean {
    val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    return try {
        FileChannel.open(Paths.get(path), setOf(CREATE_NEW, WRITE), ownerOnly).use { channel ->
            val buffer = ByteBuffer.wrap(content.encodeToByteArray())
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }
}
