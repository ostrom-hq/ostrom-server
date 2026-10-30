package dev.ostrom.server.kernel.config

import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.getenv

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

/** Reads the process environment. */
class EnvironmentConfigSource : ConfigSource {
    override fun get(key: String): String? = getenv(key)?.toKString()
}

/** A fixed set of values: a parsed configuration file, or the values of a test. */
class MapConfigSource(private val values: Map<String, String>) : ConfigSource {
    override fun get(key: String): String? = values[key]
}

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
        if (separator <= 0) throw InvalidConfigException("Line ${index + 1} of the configuration file is not KEY=value.")
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim().let {
            if (it.length >= 2 && (it.first() == '"' && it.last() == '"' || it.first() == '\'' && it.last() == '\'')) {
                it.substring(1, it.length - 1)
            } else it
        }
        key to value
    }
    .toMap()

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
