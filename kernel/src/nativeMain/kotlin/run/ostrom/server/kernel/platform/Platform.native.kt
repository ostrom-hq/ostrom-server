// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.server.kernel.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
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

/** Reads the variable with posix `getenv`. */
actual fun getEnv(name: String): String? = platform.posix.getenv(name)?.toKString()

internal actual fun createPrivateFile(path: String, content: String): Boolean {
    val temporary = "$path.${Uuid.random().toHexString()}.tmp"
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
    val linked = link(temporary, path) == 0
    val linkError = errno
    unlink(temporary)
    check(linked || linkError == EEXIST) { "Cannot create $path (errno $linkError)." }
    return linked
}

/** Mode 0600. */
private const val OWNER_READ_WRITE = 0x180
