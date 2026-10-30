package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.DatabaseConfig
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.connectPostgres
import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.uuid.Uuid

/**
 * A PostgreSQL database created for one test and dropped after it, so tests never see each other's rows.
 *
 * Configured by `OSTROM_TEST_DATABASE_URL` (an existing database to connect to, e.g.
 * `postgresql://localhost:5432/postgres`), `OSTROM_TEST_DATABASE_USER` and `OSTROM_TEST_DATABASE_PASSWORD`. When the
 * URL is unset the database tests are skipped, unless `OSTROM_TEST_DATABASE_REQUIRED=true` (set in CI), which
 * makes them fail instead: CI never passes by skipping them.
 *
 * @property driver The connection pool of the test database.
 */
class TestDatabase private constructor(
    val driver: Driver,
    private val admin: Driver,
    private val name: String,
) {
    /** Closes the pool and drops the database. */
    suspend fun close() {
        driver.close()
        admin.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)").getOrThrow()
        admin.close()
    }

    companion object {
        /**
         * Creates a fresh database.
         *
         * @return The database, or null when no test database is configured and none is required.
         */
        suspend fun create(): TestDatabase? {
            val environment = EnvironmentConfigSource()
            val url = environment["OSTROM_TEST_DATABASE_URL"]
            if (url.isNullOrBlank()) {
                check(environment["OSTROM_TEST_DATABASE_REQUIRED"] != "true") {
                    "OSTROM_TEST_DATABASE_REQUIRED is true but OSTROM_TEST_DATABASE_URL is not set."
                }
                println("OSTROM_TEST_DATABASE_URL is not set: database test skipped.")
                return null
            }
            val user = environment["OSTROM_TEST_DATABASE_USER"] ?: "postgres"
            val password = environment["OSTROM_TEST_DATABASE_PASSWORD"] ?: "postgres"
            require(Regex("postgres(ql)?://[^/?]+/[^/?]+").matches(url)) {
                "OSTROM_TEST_DATABASE_URL must be postgresql://host:port/database, without a query string."
            }
            val admin = connectPostgres(DatabaseConfig(url, user, password, maxConnections = 1))
            val name = "ostrom_test_" + Uuid.random().toHexString()
            try {
                admin.execute("CREATE DATABASE $name").getOrThrow()
            } catch (error: Throwable) {
                admin.close()
                throw error
            }
            val driver = try {
                connectPostgres(DatabaseConfig(url.substringBeforeLast('/') + "/" + name, user, password, 4))
            } catch (error: Throwable) {
                admin.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.close()
                throw error
            }
            return TestDatabase(driver, admin, name)
        }
    }
}
