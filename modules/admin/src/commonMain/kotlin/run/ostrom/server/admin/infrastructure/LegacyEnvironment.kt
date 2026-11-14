// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The former variables as the one-time import reads them: a variable `X`, or else the content of the file `X_FILE`
 * names, trimmed (a Kubernetes Secret mounted as a file, as `OSTROM_STRIPE_SECRET_KEY_FILE` and
 * `OSTROM_LICENSE_KEY_FILE` were read). An empty file counts as unset.
 */
object LegacyEnvironment {
    /**
     * @param source The server's configuration.
     * @param readFile Reads a file (the real filesystem by default).
     *
     * @return The reader of a variable: its value, or null when neither it nor its file is set.
     *
     * @throws InvalidConfigException When the reader is asked for a variable whose `_FILE` names a missing file, or
     *   one larger than 64 KiB.
     */
    fun of(source: ConfigSource, readFile: (String) -> String = ::readFile): (String) -> String? = { name ->
        source[name] ?: source["${name}_FILE"]?.let { readFile(it).trim().ifEmpty { null } }
    }

    private fun readFile(path: String): String {
        val file = Path(path)
        if (!SystemFileSystem.exists(file)) throw InvalidConfigException("The file $path does not exist.")
        return SystemFileSystem.source(file).buffered().use { source ->
            // A setting is a few kilobytes: a larger file is not one (a log, a device), and is never read whole.
            if (source.request(MAX_FILE_BYTES + 1L)) {
                throw InvalidConfigException("The file $path is larger than 64 KiB: it holds no setting.")
            }
            source.readString()
        }
    }

    private const val MAX_FILE_BYTES = 64 * 1024
}
