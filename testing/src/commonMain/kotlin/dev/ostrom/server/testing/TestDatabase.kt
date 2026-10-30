package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.DatabaseConfig
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.connectPostgres
import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.uuid.Uuid

/**
 * A PostgreSQL database created for one test and dropped after it, so tests never see each other's rows.
 *
 * Configured by `OSTROM_TEST_DATABASE_URL`, an existing database to connect to with its credentials
 * (`postgresql://ostrom:ostrom@localhost:5432/ostrom_test`), or `OSTROM_TEST_DATABASE_USER` and
 * `OSTROM_TEST_DATABASE_PASSWORD`. When the URL is unset the database tests are skipped, unless
 * `OSTROM_TEST_DATABASE_REQUIRED=true` (set in CI), which makes them fail instead: CI never passes by skipping them.
 *
 * @property driver The connection pool of the test database.
 */
class TestDatabase private constructor(
    val driver: Driver,
    private val admin: DatabaseConfig,
    private val name: String,
) {
    /** Closes the pool and drops the database. */
    suspend fun close() {
        driver.close()
        administer(admin, "DROP DATABASE IF EXISTS $name WITH (FORCE)")
    }

    /** Creating test databases. */
    companion object {
        private const val POOL_SIZE = 4

        /**
         * Creates a fresh database.
         *
         * @param environment Where the `OSTROM_TEST_DATABASE_*` keys are read; the process environment by default.
         *
         * @return The database, or null when no test database is configured and none is required.
         */
        suspend fun create(environment: ConfigSource = EnvironmentConfigSource()): TestDatabase? {
            val url = environment["OSTROM_TEST_DATABASE_URL"]
            if (url == null) {
                check(environment["OSTROM_TEST_DATABASE_REQUIRED"] != "true") {
                    "OSTROM_TEST_DATABASE_REQUIRED is true but OSTROM_TEST_DATABASE_URL is not set."
                }
                println("OSTROM_TEST_DATABASE_URL is not set: database test skipped.")
                return null
            }
            val config = DatabaseConfig.of(
                url = url,
                username = environment["OSTROM_TEST_DATABASE_USER"],
                password = environment["OSTROM_TEST_DATABASE_PASSWORD"],
                maxConnections = 1,
            )
            val name = "ostrom_test_" + Uuid.random().toHexString()
            administer(config, "CREATE DATABASE $name")
            val testUrl = config.url.substringBeforeLast('/') + "/" + name
            val driver = connectPostgres(config.copy(url = testUrl, maxConnections = POOL_SIZE))
            return TestDatabase(driver, config, name)
        }

        /** Runs [sql] on a connection to the configured database opened for it alone, closed whatever happens. */
        private suspend fun administer(config: DatabaseConfig, sql: String) {
            val admin = connectPostgres(config)
            try {
                admin.execute(sql).getOrThrow()
            } finally {
                admin.close()
            }
        }
    }
}
