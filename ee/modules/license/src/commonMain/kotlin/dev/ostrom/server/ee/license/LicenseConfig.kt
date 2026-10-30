// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
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

    companion object {
        /**
         * Reads the configuration. The direct value wins over the file, as for billing's secrets.
         *
         * @param source The configuration.
         * @param readFile Reads the key file (the real filesystem by default).
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When the key file is missing or empty: an operator who set it expects a
         *   license.
         */
        fun load(source: ConfigSource, readFile: (String) -> String = ::readLicenseFile): LicenseConfig {
            source["OSTROM_LICENSE_KEY"]?.let { return LicenseConfig(it.trim()) }
            val path = source["OSTROM_LICENSE_KEY_FILE"] ?: return LicenseConfig(null)
            val key = readFile(path).trim()
            if (key.isEmpty()) throw InvalidConfigException("License key file $path is empty.")
            return LicenseConfig(key)
        }
    }
}

private fun readLicenseFile(path: String): String {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) throw InvalidConfigException("License key file $path does not exist.")
    return SystemFileSystem.source(file).buffered().use { it.readString() }
}
