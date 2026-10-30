package dev.ostrom.server.kernel.config

import dev.ostrom.core.telemetry.TelemetrySettings
import dev.ostrom.server.kernel.logging.LogLevel

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
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    val signingKeyFile: String,
    val logLevel: LogLevel,
    val telemetry: TelemetrySettings = TelemetrySettings(serviceName = SERVICE_NAME),
) {
    companion object {
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
            port = source.int("OSTROM_PORT", 8080, 1..65535),
            database = DatabaseConfig(
                url = source.required("OSTROM_DATABASE_URL"),
                username = source.required("OSTROM_DATABASE_USER"),
                password = source.required("OSTROM_DATABASE_PASSWORD"),
                maxConnections = source.int("OSTROM_DATABASE_MAX_CONNECTIONS", 10, 1..1000),
            ),
            signingKeyFile = source["OSTROM_SIGNING_KEY_FILE"] ?: "data/instance-key.pem",
            logLevel = source["OSTROM_LOG_LEVEL"]?.let { value ->
                LogLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw InvalidConfigException("OSTROM_LOG_LEVEL must be one of ${LogLevel.entries.joinToString()}.")
            } ?: LogLevel.INFO,
            telemetry = try {
                TelemetrySettings.fromEnvironment({ source[it] }, SERVICE_NAME)
            } catch (error: IllegalArgumentException) {
                // The message names the variable, never its value (it may hold a key).
                throw InvalidConfigException(error.message ?: "Invalid telemetry settings.")
            },
        )

        /** The default `service.name` of the server's telemetry. */
        const val SERVICE_NAME: String = "ostrom-server"

        /**
         * Reads the configuration from the environment, then from the file named by `OSTROM_CONFIG_FILE` if set.
         * The environment wins over the file.
         *
         * @return The validated configuration.
         *
         * @throws InvalidConfigException When a required value is missing or a value is invalid.
         */
        fun load(): ServerConfig {
            val environment = EnvironmentConfigSource()
            val file = environment["OSTROM_CONFIG_FILE"]?.let { MapConfigSource(readConfigFile(it)) }
            return from(LayeredConfigSource(listOfNotNull(environment, file)))
        }

        private fun ConfigSource.required(key: String): String =
            this[key]?.takeIf { it.isNotBlank() } ?: throw InvalidConfigException("$key is required.")

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
    override fun toString(): String = "DatabaseConfig(url=$url, username=$username, password=***, maxConnections=$maxConnections)"
}
