// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.config

import dev.ostrom.core.telemetry.TelemetrySettings
import dev.ostrom.server.kernel.http.ReadLimits
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
 * @property telemetry Where traces, metrics, logs and product events go (the standard `OTEL_*` variables,
 *   `OSTROM_LANGFUSE_*`, `OSTROM_POSTHOG_*`); nowhere by default.
 * @property instanceId The `service.instance.id` of the telemetry (`OSTROM_POD_NAME`, the pod name in Kubernetes);
 *   null to draw a random one at start.
 * @property trustedProxies The reverse proxies whose `X-Forwarded-For` is believed (`OSTROM_TRUSTED_PROXIES`), and
 *   their client address header (`OSTROM_CLIENT_ADDRESS_HEADER`).
 * @property drainDelay How long the server keeps serving, `/ready` at 503, between SIGTERM and stopping the engine
 *   (`OSTROM_SHUTDOWN_DELAY_SECONDS`, 0 to 10), so the load balancer stops sending it requests first. With the 15 s
 *   the engine then gives requests in flight, a stop takes at most 25 s, leaving 5 s of the Helm chart's 30 s grace
 *   period to close the pool and the workers.
 * @property readLimits How much one subject may read of the costly routes (`OSTROM_READS_PER_MINUTE`,
 *   `OSTROM_READS_IN_FLIGHT`, `OSTROM_READ_WAITS_IN_FLIGHT`).
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    val signingKeyFile: String,
    val logLevel: LogLevel,
    val telemetry: TelemetrySettings = TelemetrySettings(serviceName = SERVICE_NAME),
    val instanceId: String? = null,
    val trustedProxies: TrustedProxies = TrustedProxies.NONE,
    val drainDelay: Duration = DEFAULT_DRAIN_DELAY_SECONDS.seconds,
    val readLimits: ReadLimits =
        ReadLimits(ReadLimits.DEFAULT_PER_MINUTE, ReadLimits.DEFAULT_IN_FLIGHT, ReadLimits.DEFAULT_WAITS_IN_FLIGHT),
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
        private const val MAX_READS_PER_MINUTE = 100_000
        private const val MAX_READS_IN_FLIGHT = 1000
        private const val MAX_READ_WAITS_IN_FLIGHT = 10_000

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
            telemetry = try {
                TelemetrySettings.fromEnvironment({ source[it] }, SERVICE_NAME)
            } catch (error: IllegalArgumentException) {
                // The message names the variable, never its value (it may hold a key).
                throw InvalidConfigException("Invalid telemetry settings: ${error.message}", error)
            },
            instanceId = source["OSTROM_POD_NAME"],
            trustedProxies = TrustedProxies.parse(
                source["OSTROM_TRUSTED_PROXIES"],
                source["OSTROM_CLIENT_ADDRESS_HEADER"],
            ),
            drainDelay = source.int(
                "OSTROM_SHUTDOWN_DELAY_SECONDS",
                DEFAULT_DRAIN_DELAY_SECONDS,
                0..MAX_DRAIN_DELAY_SECONDS,
            ).seconds,
            readLimits = readLimits(source),
        )

        /**
         * The read limits of the costly routes (AUDIT-SECU-LECTURES M1), per subject and per replica:
         * `OSTROM_READS_PER_MINUTE` (1 to 100 000, 600 by default), `OSTROM_READS_IN_FLIGHT` (1 to 1 000, 16 by
         * default) and `OSTROM_READ_WAITS_IN_FLIGHT` (1 to 10 000, 256 by default).
         *
         * @param source Where values are read from.
         *
         * @return The limits.
         *
         * @throws InvalidConfigException When a value is not an integer in its range.
         */
        fun readLimits(source: ConfigSource): ReadLimits = ReadLimits(
            perMinute = source.int("OSTROM_READS_PER_MINUTE", ReadLimits.DEFAULT_PER_MINUTE, 1..MAX_READS_PER_MINUTE),
            inFlight = source.int("OSTROM_READS_IN_FLIGHT", ReadLimits.DEFAULT_IN_FLIGHT, 1..MAX_READS_IN_FLIGHT),
            waitsInFlight = source.int(
                "OSTROM_READ_WAITS_IN_FLIGHT",
                ReadLimits.DEFAULT_WAITS_IN_FLIGHT,
                1..MAX_READ_WAITS_IN_FLIGHT,
            ),
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

        /** The default `service.name` of the server's telemetry. */
        const val SERVICE_NAME: String = "ostrom-server"

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
