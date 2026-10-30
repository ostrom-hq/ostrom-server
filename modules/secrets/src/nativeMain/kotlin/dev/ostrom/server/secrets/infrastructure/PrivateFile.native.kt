// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.server.secrets.infrastructure

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.write

/** posix `open` with `O_CREAT | O_EXCL` and mode 0600. */
internal actual fun createOwnerOnlyFile(path: String, content: String): Boolean {
    val descriptor = open(path, O_WRONLY or O_CREAT or O_EXCL, OWNER_READ_WRITE)
    if (descriptor < 0) {
        check(errno == EEXIST) { "Cannot create $path (errno $errno)." }
        return false
    }
    try {
        val bytes = content.encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val written = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
                check(written > 0) { "Cannot write $path (errno $errno)." }
                offset += written.toInt()
            }
        }
        check(fsync(descriptor) == 0) { "Cannot flush $path (errno $errno)." }
    } finally {
        close(descriptor)
    }
    return true
}

/** Mode 0600. */
private const val OWNER_READ_WRITE = 0x180
