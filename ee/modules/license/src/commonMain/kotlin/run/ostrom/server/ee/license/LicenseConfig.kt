// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The license module's configuration (docs/MODULES.md table).
 *
 * @property licenseKey `OSTROM_LICENSE_KEY`, or the content of `OSTROM_LICENSE_KEY_FILE`; null without a license.
 */
class LicenseConfig(val licenseKey: String?) {
    /** Never prints the key, so the configuration can be logged or appear in an error. */
    override fun toString(): String = "LicenseConfig(licenseKey=${if (licenseKey == null) "unset" else "set"})"

    /** Reads the configuration. */
    companion object {
        /**
         * Reads the configuration. The direct value wins over the file, as for billing's secrets.
         *
         * @param source The configuration.
         * @param readFile Reads the key file (the real filesystem by default).
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When the key file is missing, empty or larger than 16 KiB: an operator who
         *   set it expects a license.
         */
        fun load(source: ConfigSource, readFile: (String) -> String = ::readLicenseFile): LicenseConfig {
            val direct = source["OSTROM_LICENSE_KEY"]
            val path = source["OSTROM_LICENSE_KEY_FILE"]
            val key = when {
                direct != null -> direct.trim()
                path != null -> readFile(path).trim().ifEmpty {
                    throw InvalidConfigException("License key file $path is empty.")
                }
                else -> null
            }
            return LicenseConfig(key)
        }
    }
}

/** A license key is at most 8 192 characters: a larger file is not one (a log, a device), and is not read whole. */
internal const val MAX_LICENSE_FILE_BYTES = 16 * 1024

private fun readLicenseFile(path: String): String {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) throw InvalidConfigException("License key file $path does not exist.")
    return SystemFileSystem.source(file).buffered().use { source ->
        if (source.request(MAX_LICENSE_FILE_BYTES + 1L)) {
            throw InvalidConfigException("License key file $path is larger than 16 KiB: it is not a license key.")
        }
        source.readString()
    }
}
