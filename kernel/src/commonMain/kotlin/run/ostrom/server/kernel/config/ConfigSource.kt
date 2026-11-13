// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.config

import run.ostrom.server.kernel.platform.getEnv
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Where configuration values are read from. Keys are the environment variable names (`OSTROM_PORT`), whatever the
 * source, so that a value documented once can be set in the environment or in the configuration file.
 */
interface ConfigSource {
    /**
     * Returns the raw value of a key.
     *
     * @param key The configuration key, an environment variable name such as `OSTROM_PORT`.
     *
     * @return The value, or null when this source does not set the key.
     */
    operator fun get(key: String): String?
}

/** Reads the process environment; a variable set to a blank value counts as unset. */
class EnvironmentConfigSource : ConfigSource {
    override fun get(key: String): String? = getEnv(key).unlessBlank()
}

/** A fixed set of values (a parsed configuration file, the values of a test); a blank value counts as unset. */
class MapConfigSource(private val values: Map<String, String>) : ConfigSource {
    override fun get(key: String): String? = values[key].unlessBlank()
}

/** A blank value counts as unset, in every source. */
internal fun String?.unlessBlank(): String? = this?.takeIf { it.isNotBlank() }

/** Asks each source in order and keeps the first value found: the environment comes before the file. */
class LayeredConfigSource(private val sources: List<ConfigSource>) : ConfigSource {
    override fun get(key: String): String? = sources.firstNotNullOfOrNull { it[key] }
}

/**
 * Parses a configuration file of `KEY=value` lines. Blank lines and lines starting with `#` are ignored; a value
 * may be wrapped in single or double quotes, which are removed.
 *
 * @param content The text of the file.
 *
 * @return The values by key.
 *
 * @throws InvalidConfigException When a line is neither blank, a comment, nor `KEY=value`.
 */
fun parseConfigFile(content: String): Map<String, String> = content.lineSequence()
    .mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
        val separator = line.indexOf('=')
        if (separator <= 0) {
            throw InvalidConfigException("Line ${index + 1} of the configuration file is not KEY=value.")
        }
        line.substring(0, separator).trim() to unquote(line.substring(separator + 1).trim())
    }
    .toMap()

private fun unquote(value: String): String {
    val quoted = value.length >= 2 && value.first() in QUOTES && value.last() == value.first()
    return if (quoted) value.substring(1, value.length - 1) else value
}

private val QUOTES = setOf('"', '\'')

/**
 * Reads and parses a configuration file.
 *
 * @param path The path of the file.
 *
 * @return The values by key.
 *
 * @throws InvalidConfigException When the file is missing or malformed.
 */
fun readConfigFile(path: String): Map<String, String> {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) throw InvalidConfigException("Configuration file $path does not exist.")
    val content = SystemFileSystem.source(file).buffered().use { it.readString() }
    return parseConfigFile(content)
}
