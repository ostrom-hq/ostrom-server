// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.config

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.network.TrustedProxies
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the server needs to start, read once at boot from the environment and the optional configuration
 * file (`OSTROM_CONFIG_FILE`). Every key is documented in docs/MODULES.md.
 *
 * @property host The address the HTTP server listens on.
 * @property port The HTTP port.
 * @property database How to reach PostgreSQL.
 * @property signingKeyFile The PEM file (PKCS#8) of the instance's Ed25519 key, created on first start if missing.
 * @property logLevel The lowest level written to the logs.
 * @property trustedProxies The reverse proxies whose `X-Forwarded-For` is believed (`OSTROM_TRUSTED_PROXIES`).
 * @property drainDelay How long the server keeps serving, `/ready` at 503, between SIGTERM and stopping the engine
 *   (`OSTROM_SHUTDOWN_DELAY_SECONDS`, 0 to 10), so the load balancer stops sending it requests first. With the 15 s
 *   the engine then gives requests in flight, a stop takes at most 25 s, leaving 5 s of the Helm chart's 30 s grace
 *   period to close the pool and the workers.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    val signingKeyFile: String,
    val logLevel: LogLevel,
    val trustedProxies: TrustedProxies = TrustedProxies.NONE,
    val drainDelay: Duration = DEFAULT_DRAIN_DELAY_SECONDS.seconds,
) {
    /** Reading the configuration. */
    companion object {
        private const val DEFAULT_PORT = 8080
        private const val MAX_PORT = 65535
        private const val DEFAULT_MAX_CONNECTIONS = 10
        private const val MAX_CONNECTIONS = 1000
        private const val DEFAULT_DRAIN_DELAY_SECONDS = 5
        private const val MAX_DRAIN_DELAY_SECONDS = 10
        private const val MIGRATION_CONNECTIONS = 2

        /**
         * Reads the configuration from a source.
         *
         * @param source Where values are read from.
         *
         * @return The validated configuration.
         *
         * @throws InvalidConfigException When a required value is missing or a value is invalid.
         */
        fun from(source: ConfigSource): ServerConfig = ServerConfig(
            host = source["OSTROM_HOST"] ?: "0.0.0.0",
            port = source.int("OSTROM_PORT", DEFAULT_PORT, 1..MAX_PORT),
            database = DatabaseConfig.of(
                url = source["OSTROM_DATABASE_URL"] ?: throw InvalidConfigException("OSTROM_DATABASE_URL is required."),
                username = source["OSTROM_DATABASE_USER"],
                password = source["OSTROM_DATABASE_PASSWORD"],
                maxConnections = source.int(
                    "OSTROM_DATABASE_MAX_CONNECTIONS",
                    DEFAULT_MAX_CONNECTIONS,
                    1..MAX_CONNECTIONS,
                ),
            ),
            signingKeyFile = source["OSTROM_SIGNING_KEY_FILE"] ?: "data/instance-key.pem",
            logLevel = logLevel(source["OSTROM_LOG_LEVEL"]),
            trustedProxies = TrustedProxies.parse(source["OSTROM_TRUSTED_PROXIES"]),
            drainDelay = source.int(
                "OSTROM_SHUTDOWN_DELAY_SECONDS",
                DEFAULT_DRAIN_DELAY_SECONDS,
                0..MAX_DRAIN_DELAY_SECONDS,
            ).seconds,
        )

        /**
         * The owner's connection, for `ostrom-server migrate` only: `OSTROM_DATABASE_MIGRATION_URL`, with
         * `OSTROM_DATABASE_MIGRATION_USER` and `OSTROM_DATABASE_MIGRATION_PASSWORD` when the URL carries no
         * credentials. The serving process never reads these keys (CONVENTIONS 16.1).
         *
         * @param source Where values are read from.
         *
         * @return The owner's connection, two connections wide (the migration lock and the migrations).
         *
         * @throws InvalidConfigException When the URL is missing or invalid, or the credentials are given nowhere.
         */
        fun migrationDatabase(source: ConfigSource): DatabaseConfig = DatabaseConfig.of(
            url = source["OSTROM_DATABASE_MIGRATION_URL"]
                ?: throw InvalidConfigException("OSTROM_DATABASE_MIGRATION_URL is required to migrate."),
            username = source["OSTROM_DATABASE_MIGRATION_USER"],
            password = source["OSTROM_DATABASE_MIGRATION_PASSWORD"],
            maxConnections = MIGRATION_CONNECTIONS,
            keys = "OSTROM_DATABASE_MIGRATION",
        )

        /**
         * The configuration source of the process: the environment, then the file named by `OSTROM_CONFIG_FILE` if
         * set. The environment wins over the file. Bound in Koin, so modules read their own keys from it.
         *
         * @param environment The process environment; replaced in tests.
         *
         * @return The layered source.
         *
         * @throws InvalidConfigException When the configuration file is missing or malformed.
         */
        fun source(environment: ConfigSource = EnvironmentConfigSource()): ConfigSource {
            val file = environment["OSTROM_CONFIG_FILE"]?.let { MapConfigSource(readConfigFile(it)) }
            return LayeredConfigSource(listOfNotNull(environment, file))
        }

        /**
         * Reads the configuration from [source] of [environment].
         *
         * @param environment The process environment; replaced in tests.
         *
         * @return The validated configuration.
         *
         * @throws InvalidConfigException When a required value is missing or a value is invalid.
         */
        fun load(environment: ConfigSource = EnvironmentConfigSource()): ServerConfig = from(source(environment))

        private fun logLevel(value: String?): LogLevel = if (value == null) {
            LogLevel.INFO
        } else {
            LogLevel.entries.firstOrNull { it.name.equals(value, true) }
                ?: throw InvalidConfigException("OSTROM_LOG_LEVEL must be one of ${LogLevel.entries.joinToString()}.")
        }

        private fun ConfigSource.int(key: String, default: Int, range: IntRange): Int {
            val raw = this[key] ?: return default
            val value = raw.toIntOrNull() ?: throw InvalidConfigException("$key must be an integer.")
            if (value !in range) throw InvalidConfigException("$key must be between ${range.first} and ${range.last}.")
            return value
        }
    }
}

/**
 * How to reach PostgreSQL.
 *
 * @property url The connection URL without credentials, e.g. `postgresql://localhost:5432/ostrom`.
 * @property username The database user.
 * @property password The database password.
 * @property maxConnections The size of the connection pool.
 */
data class DatabaseConfig(
    val url: String,
    val username: String,
    val password: String,
    val maxConnections: Int,
) {
    override fun toString(): String =
        "DatabaseConfig(url=$url, username=$username, password=***, maxConnections=$maxConnections)"

    /** Reading a database URL. */
    companion object {
        private val URL = Regex("(postgres(?:ql)?://)(?:([^:@/?#]*)(?::([^@/?#]*))?@)?([^@/?#]+/[^@/?#]+)")

        /**
         * Builds the configuration from a URL that may carry the credentials
         * (`postgresql://user:password@host:5432/database`). An explicit user or password wins over the URL's.
         *
         * @param url The connection URL, with or without credentials, without a query string.
         * @param username The user, or null to take the URL's.
         * @param password The password, or null to take the URL's.
         * @param maxConnections The size of the connection pool.
         * @param keys The prefix of the configuration keys named in errors (`<keys>_USER`, `<keys>_PASSWORD`).
         *
         * @return The configuration, its URL without credentials.
         *
         * @throws InvalidConfigException When the URL is not a PostgreSQL URL with a database, or the user or the
         *   password is given nowhere.
         */
        fun of(
            url: String,
            username: String?,
            password: String?,
            maxConnections: Int,
            keys: String = "OSTROM_DATABASE",
        ): DatabaseConfig {
            val match = URL.matchEntire(url) ?: throw InvalidConfigException(
                "The database URL must be postgresql://[user[:password]@]host[:port]/database.",
            )
            val (scheme, urlUser, urlPassword) = match.destructured
            val location = match.groupValues.last()
            if ('%' in urlUser + urlPassword) {
                throw InvalidConfigException(
                    "Percent-encoded credentials are not supported in the database URL: set ${keys}_USER " +
                        "and ${keys}_PASSWORD instead.",
                )
            }
            return DatabaseConfig(
                url = scheme + location,
                username = credential(username, urlUser, "user", "${keys}_USER"),
                password = credential(password, urlPassword, "password", "${keys}_PASSWORD"),
                maxConnections = maxConnections,
            )
        }

        private fun credential(explicit: String?, fromUrl: String, what: String, key: String): String =
            explicit ?: fromUrl.ifEmpty {
                throw InvalidConfigException("The database $what is required: in the URL or $key.")
            }
    }
}
