// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.server.secrets.infrastructure

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
import platform.posix.link
import platform.posix.open
import platform.posix.unlink
import platform.posix.write
import kotlin.uuid.Uuid

/** posix `open` with `O_CREAT | O_EXCL` and mode 0600 for the temporary file, then `link`, which fails on [path]. */
internal actual fun createOwnerOnlyFile(path: String, content: String): Boolean {
    val temporary = "$path.${Uuid.random()}.tmp"
    val descriptor = open(temporary, O_WRONLY or O_CREAT or O_EXCL, OWNER_READ_WRITE)
    check(descriptor >= 0) { "Cannot create $temporary (errno $errno)." }
    try {
        val bytes = content.encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val written = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
                check(written > 0) { "Cannot write $temporary (errno $errno)." }
                offset += written.toInt()
            }
        }
        check(fsync(descriptor) == 0) { "Cannot flush $temporary (errno $errno)." }
    } finally {
        close(descriptor)
    }
    try {
        if (link(temporary, path) == 0) return true
        check(errno == EEXIST) { "Cannot create $path (errno $errno)." }
        return false
    } finally {
        unlink(temporary)
    }
}

/** Mode 0600. */
private const val OWNER_READ_WRITE = 0x180
