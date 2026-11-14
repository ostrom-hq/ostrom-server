// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Reads a whole text file: the server pod's service account of an in-cluster cluster, or a former variable's file.
 *
 * @param path The file.
 *
 * @return Its content, or null when it does not exist or cannot be read.
 */
fun readTextFileOrNull(path: String): String? = try {
    SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
} catch (_: IOException) {
    null
}
