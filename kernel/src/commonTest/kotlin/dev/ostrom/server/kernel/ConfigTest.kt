package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.config.parseConfigFile
import dev.ostrom.server.kernel.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigTest {
    private val database = mapOf(
        "OSTROM_DATABASE_URL" to "postgresql://localhost:5432/ostrom",
        "OSTROM_DATABASE_USER" to "ostrom",
        "OSTROM_DATABASE_PASSWORD" to "secret",
    )

    @Test
    fun parsesKeyValueLinesCommentsAndQuotes() {
        val values = parseConfigFile(
            """
            # comment
            OSTROM_PORT=9090

            OSTROM_HOST = "127.0.0.1"
            OSTROM_LOG_LEVEL='debug'
            """.trimIndent(),
        )
        assertEquals(mapOf("OSTROM_PORT" to "9090", "OSTROM_HOST" to "127.0.0.1", "OSTROM_LOG_LEVEL" to "debug"), values)
    }

    @Test
    fun rejectsALineWithoutEquals() {
        assertFailsWith<InvalidConfigException> { parseConfigFile("OSTROM_PORT") }
    }

    @Test
    fun appliesDefaults() {
        val config = ServerConfig.from(MapConfigSource(database))
        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals(10, config.database.maxConnections)
        assertEquals(LogLevel.INFO, config.logLevel)
        assertEquals("data/instance-key.pem", config.signingKeyFile)
    }

    @Test
    fun environmentWinsOverFile() {
        val environment = MapConfigSource(database + ("OSTROM_PORT" to "7000"))
        val file = MapConfigSource(mapOf("OSTROM_PORT" to "9000", "OSTROM_LOG_LEVEL" to "warn"))
        val config = ServerConfig.from(LayeredConfigSource(listOf(environment, file)))
        assertEquals(7000, config.port)
        assertEquals(LogLevel.WARN, config.logLevel)
    }

    @Test
    fun requiresTheDatabase() {
        val error = assertFailsWith<InvalidConfigException> { ServerConfig.from(MapConfigSource(emptyMap())) }
        assertEquals("OSTROM_DATABASE_URL is required.", error.message)
    }

    @Test
    fun rejectsAnInvalidPortOrLevel() {
        assertFailsWith<InvalidConfigException> { ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to "0"))) }
        assertFailsWith<InvalidConfigException> { ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to "http"))) }
        assertFailsWith<InvalidConfigException> { ServerConfig.from(MapConfigSource(database + ("OSTROM_LOG_LEVEL" to "loud"))) }
    }

    @Test
    fun neverPrintsThePassword() {
        assertFalse("secret" in ServerConfig.from(MapConfigSource(database)).toString())
    }
}
