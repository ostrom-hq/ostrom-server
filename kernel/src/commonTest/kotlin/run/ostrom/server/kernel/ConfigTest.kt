// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.config.DatabaseConfig
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.LayeredConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.config.ServerConfig
import run.ostrom.server.kernel.config.parseConfigFile
import run.ostrom.server.kernel.config.readConfigFile
import run.ostrom.server.kernel.http.CallCeilingSettings
import run.ostrom.server.kernel.http.ReadLimits
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.network.TrustedProxies
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

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
            ONE_CHAR=a
            HALF_QUOTED="open
            MIXED_QUOTES="mixed'
            PLAIN=two words
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "OSTROM_PORT" to "9090",
                "OSTROM_HOST" to "127.0.0.1",
                "OSTROM_LOG_LEVEL" to "debug",
                "ONE_CHAR" to "a",
                "HALF_QUOTED" to "\"open",
                "MIXED_QUOTES" to "\"mixed'",
                "PLAIN" to "two words",
            ),
            values,
        )
    }

    @Test
    fun rejectsALineWithoutAKey() {
        val missingEquals = assertFailsWith<InvalidConfigException> { parseConfigFile("OSTROM_PORT") }
        assertEquals("Line 1 of the configuration file is not KEY=value.", missingEquals.message)
        assertFailsWith<InvalidConfigException> { parseConfigFile("# ok\n=8080") }
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
    fun readsEveryKey() {
        val config = ServerConfig.from(
            MapConfigSource(
                database + mapOf(
                    "OSTROM_HOST" to "127.0.0.1",
                    "OSTROM_PORT" to "9090",
                    "OSTROM_DATABASE_MAX_CONNECTIONS" to "4",
                    "OSTROM_SIGNING_KEY_FILE" to "/var/run/ostrom/key.pem",
                    "OSTROM_LOG_LEVEL" to "Debug",
                ),
            ),
        )
        assertEquals(
            ServerConfig(
                host = "127.0.0.1",
                port = 9090,
                database = DatabaseConfig("postgresql://localhost:5432/ostrom", "ostrom", "secret", 4),
                signingKeyFile = "/var/run/ostrom/key.pem",
                logLevel = LogLevel.DEBUG,
            ),
            config,
        )
    }

    @Test
    fun aBlankValueCountsAsUnset() {
        assertEquals(8080, ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to " "))).port)
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
    fun loadsTheEnvironmentAlone() {
        assertEquals(8080, ServerConfig.load(MapConfigSource(database)).port)
    }

    @Test
    fun loadsTheFileNamedByTheEnvironment() {
        val path = Path(SystemTemporaryDirectory, "ostrom-config-${Uuid.random().toHexString()}.env")
        try {
            SystemFileSystem.sink(path).buffered().use { it.writeString("OSTROM_PORT=9191\nOSTROM_HOST=10.0.0.1\n") }
            val environment = MapConfigSource(
                database + mapOf("OSTROM_CONFIG_FILE" to path.toString(), "OSTROM_HOST" to "::"),
            )
            val config = ServerConfig.load(environment)
            assertEquals(9191, config.port)
            assertEquals("::", config.host)
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun aMissingConfigurationFileFailsTheStart() {
        val error = assertFailsWith<InvalidConfigException> { readConfigFile("/nonexistent/ostrom.env") }
        assertEquals("Configuration file /nonexistent/ostrom.env does not exist.", error.message)
    }

    @Test
    fun readsTheProcessEnvironment() {
        val environment = EnvironmentConfigSource()
        assertNotNull(environment["PATH"], "PATH is set in every test process")
        assertEquals(environment["PATH"], ServerConfig.source()["PATH"], "The process source reads the environment")
        assertNull(environment["OSTROM_SURELY_UNSET_${Uuid.random().toHexString()}"])
    }

    @Test
    fun requiresTheDatabase() {
        val error = assertFailsWith<InvalidConfigException> { ServerConfig.from(MapConfigSource(emptyMap())) }
        assertEquals("OSTROM_DATABASE_URL is required.", error.message)
    }

    @Test
    fun takesTheCredentialsFromTheUrl() {
        val config = DatabaseConfig.of("postgresql://ostrom:pa55@localhost:5432/ostrom_test", null, null, 3)
        assertEquals(DatabaseConfig("postgresql://localhost:5432/ostrom_test", "ostrom", "pa55", 3), config)
    }

    @Test
    fun explicitCredentialsWinOverTheUrl() {
        val config = DatabaseConfig.of("postgres://ostrom:pa55@db/ostrom", "admin", "other", 1)
        assertEquals(DatabaseConfig("postgres://db/ostrom", "admin", "other", 1), config)
    }

    @Test
    fun requiresAUserAndAPassword() {
        val noUser = assertFailsWith<InvalidConfigException> {
            DatabaseConfig.of("postgresql://db/ostrom", null, "x", 1)
        }
        assertEquals("The database user is required: in the URL or OSTROM_DATABASE_USER.", noUser.message)
        val noPassword = assertFailsWith<InvalidConfigException> {
            DatabaseConfig.of("postgresql://u@db/ostrom", null, null, 1)
        }
        assertEquals("The database password is required: in the URL or OSTROM_DATABASE_PASSWORD.", noPassword.message)
    }

    @Test
    fun rejectsAnUrlThatIsNotPostgresWithADatabase() {
        assertFailsWith<InvalidConfigException> { DatabaseConfig.of("mysql://db/ostrom", "u", "p", 1) }
        assertFailsWith<InvalidConfigException> { DatabaseConfig.of("postgresql://db", "u", "p", 1) }
        assertFailsWith<InvalidConfigException> {
            DatabaseConfig.of("postgresql://db/ostrom?sslmode=require", "u", "p", 1)
        }
    }

    @Test
    fun rejectsPercentEncodedCredentials() {
        assertFailsWith<InvalidConfigException> { DatabaseConfig.of("postgresql://us%40er:p@db/ostrom", null, null, 1) }
        assertFailsWith<InvalidConfigException> {
            DatabaseConfig.of("postgresql://user:p%40ss@db/ostrom", null, null, 1)
        }
    }

    @Test
    fun rejectsAnInvalidPortOrLevel() {
        val outOfRange = assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to "0")))
        }
        assertEquals("OSTROM_PORT must be between 1 and 65535.", outOfRange.message)
        assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to "65536")))
        }
        val notANumber = assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + ("OSTROM_PORT" to "http")))
        }
        assertEquals("OSTROM_PORT must be an integer.", notANumber.message)
        assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + ("OSTROM_LOG_LEVEL" to "loud")))
        }
    }

    @Test
    fun neverPrintsThePassword() {
        val printed = ServerConfig.from(MapConfigSource(database)).toString()
        assertFalse("secret" in printed)
        assertEquals(
            "DatabaseConfig(url=postgresql://localhost:5432/ostrom, username=ostrom, password=***, maxConnections=10)",
            ServerConfig.from(MapConfigSource(database)).database.toString(),
        )
    }

    @Test
    fun readsTheTrustedProxiesAndTheDrainDelay() {
        val defaults = ServerConfig.from(MapConfigSource(database))
        assertSame(TrustedProxies.NONE, defaults.trustedProxies)
        assertEquals(5.seconds, defaults.drainDelay)
        val config = ServerConfig.from(
            MapConfigSource(
                database + mapOf("OSTROM_TRUSTED_PROXIES" to "10.0.0.0/8", "OSTROM_SHUTDOWN_DELAY_SECONDS" to "0"),
            ),
        )
        assertEquals("198.51.100.1", config.trustedProxies.clientAddress("10.0.0.5", listOf("198.51.100.1")))
        assertEquals(Duration.ZERO, config.drainDelay)
        val longest = ServerConfig.from(MapConfigSource(database + mapOf("OSTROM_SHUTDOWN_DELAY_SECONDS" to "10")))
        assertEquals(10.seconds, longest.drainDelay)
        assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + mapOf("OSTROM_SHUTDOWN_DELAY_SECONDS" to "11")))
        }
        assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + mapOf("OSTROM_TRUSTED_PROXIES" to "everyone")))
        }
        val cloudflare = ServerConfig.from(
            MapConfigSource(
                database + mapOf(
                    "OSTROM_TRUSTED_PROXIES" to "10.0.0.0/8",
                    "OSTROM_CLIENT_ADDRESS_HEADER" to "CF-Connecting-IP",
                ),
            ),
        )
        assertEquals("CF-Connecting-IP", cloudflare.trustedProxies.clientHeader)
        assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + mapOf("OSTROM_CLIENT_ADDRESS_HEADER" to "CF-Connecting-IP")))
        }
    }

    @Test
    fun readsTheReadLimitsWithinTheirBounds() {
        assertEquals(ReadLimits(600, 16, 256), ServerConfig.from(MapConfigSource(database)).readLimits)
        val set = mapOf(
            "OSTROM_READS_PER_MINUTE" to "100000",
            "OSTROM_READS_IN_FLIGHT" to "1",
            "OSTROM_READ_WAITS_IN_FLIGHT" to "10000",
        )
        assertEquals(ReadLimits(100_000, 1, 10_000), ServerConfig.from(MapConfigSource(database + set)).readLimits)
        val wrongs = listOf(
            "OSTROM_READS_PER_MINUTE" to "0",
            "OSTROM_READS_IN_FLIGHT" to "1001",
            "OSTROM_READS_IN_FLIGHT" to "many",
            "OSTROM_READ_WAITS_IN_FLIGHT" to "0",
        )
        for (wrong in wrongs) {
            assertFailsWith<InvalidConfigException>(wrong.toString()) {
                ServerConfig.from(MapConfigSource(database + wrong))
            }
        }
    }

    @Test
    fun readsTheCallCeilingsWithinTheirBounds() {
        assertEquals(CallCeilingSettings(), ServerConfig.from(MapConfigSource(database)).callCeilings)
        val set = mapOf(
            "OSTROM_MAX_CALLS_IN_PROGRESS" to "1",
            "OSTROM_MAX_HELD_CALLS" to "100000",
            "OSTROM_MAX_OPEN_CONNECTIONS" to "7",
            "OSTROM_CALL_QUEUE_SECONDS" to "0",
        )
        assertEquals(
            CallCeilingSettings(served = 1, held = 100_000, open = 7, queueWait = Duration.ZERO),
            ServerConfig.from(MapConfigSource(database + set)).callCeilings,
        )
        val wrongs = listOf(
            "OSTROM_MAX_CALLS_IN_PROGRESS" to "0",
            "OSTROM_MAX_CALLS_IN_PROGRESS" to "many",
            "OSTROM_MAX_HELD_CALLS" to "100001",
            "OSTROM_CALL_QUEUE_SECONDS" to "61",
            "OSTROM_MAX_OPEN_CONNECTIONS" to "0",
        )
        for (wrong in wrongs) {
            assertFailsWith<InvalidConfigException>(wrong.toString()) {
                ServerConfig.from(MapConfigSource(database + wrong))
            }
        }
    }

    @Test
    fun readsTheMigrationConnectionFromItsOwnKeysOnly() {
        val config = ServerConfig.migrationDatabase(
            MapConfigSource(
                database + mapOf(
                    "OSTROM_DATABASE_MIGRATION_URL" to "postgresql://owner:hunter2@db:5432/ostrom",
                    "OSTROM_DATABASE_MIGRATION_PASSWORD" to "explicit",
                ),
            ),
        )
        assertEquals(DatabaseConfig("postgresql://db:5432/ostrom", "owner", "explicit", 2), config)
        val missing = assertFailsWith<InvalidConfigException> {
            ServerConfig.migrationDatabase(MapConfigSource(database))
        }
        assertEquals("OSTROM_DATABASE_MIGRATION_URL is required to migrate.", missing.message)
        val noUser = assertFailsWith<InvalidConfigException> {
            ServerConfig.migrationDatabase(
                MapConfigSource(database + mapOf("OSTROM_DATABASE_MIGRATION_URL" to "postgresql://db:5432/ostrom")),
            )
        }
        assertEquals(
            "The database user is required: in the URL or OSTROM_DATABASE_MIGRATION_USER.",
            noUser.message,
        )
    }
}
