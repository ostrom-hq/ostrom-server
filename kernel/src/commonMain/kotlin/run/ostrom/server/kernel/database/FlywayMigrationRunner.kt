// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.config.DatabaseConfig
import run.ostrom.server.kernel.logging.Logger
import java.sql.Connection
import java.sql.SQLException
import java.util.zip.CRC32
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.migration.Context
import org.flywaydb.core.api.migration.JavaMigration

/**
 * [MigrationRunner] on Flyway (docs/decisions/0001, step 2; CRL-05), as TeamCode's `Database.migrate`, one history
 * table per module (`flyway_<module>`) so that two modules never compete for a version number.
 *
 * The modules share one schema, so a module's first pass finds the other modules' tables: its history starts with an
 * empty baseline (version 0) instead of Flyway refusing a non-empty schema, and every version of the module runs.
 *
 * A migration waits at most 3 s for a lock (`lock_timeout`, set on the connection by [migrationUrl]); a pass that
 * gave up on one (SQLSTATE 55P03) is run again after a pause, up to [attempts] times. Each migration is its own
 * transaction, so a pass run again starts at the first migration not applied.
 *
 * @param source The database: the owner's for [migrate], any role that may read the histories for [verify].
 * @param logger Where applied migrations are reported.
 * @param lockPause The pause before a pass is run again after a lock timeout.
 * @param attempts How many times a pass runs when it keeps waiting too long for a lock.
 */
class FlywayMigrationRunner(
    private val source: DataSource,
    private val logger: Logger,
    private val lockPause: Duration = 1.seconds,
    private val attempts: Int = LOCK_ATTEMPTS,
) : MigrationRunner {
    override suspend fun migrate(module: String, migrations: List<SchemaMigration>) {
        val table = historyTable(module)
        requireVersions(module, migrations)
        if (migrations.isEmpty()) return
        var attempt = 1
        while (true) {
            val applied = try {
                withContext(Dispatchers.IO) { pass(table, migrations) }
            } catch (error: FlywayException) {
                if (attempt == attempts || !error.isLockTimeout()) throw error
                null
            } finally {
                // Even after a failed or cancelled pass: the history may have been created, and must not stay writable
                // meanwhile (AUDIT-RLS R8).
                withContext(NonCancellable + Dispatchers.IO) {
                    source.connection.use { it.run(ownerOnlySql(table)) }
                }
            }
            if (applied != null) {
                applied.forEach { (version, millis) ->
                    logger.info(
                        "migration applied",
                        mapOf("module" to module, "version" to version, "duration_ms" to millis),
                    )
                }
                logger.info(
                    "migrations checked",
                    mapOf("module" to module, "total" to migrations.size, "applied" to applied.size),
                )
                return
            }
            logger.warn("migration waited too long for a lock", mapOf("module" to module, "attempt" to attempt))
            attempt++
            delay(lockPause)
        }
    }

    override suspend fun verify(module: String, migrations: List<SchemaMigration>) {
        val table = historyTable(module)
        requireVersions(module, migrations)
        if (migrations.isEmpty()) return
        val applied = withContext(Dispatchers.IO) { source.connection.use { history(it, table) } }
        val done = applied.filter { it.success }.associate { it.version to it.checksum }
        val missing = migrations.filter { done[it.version] != checksum(it) }.map { it.version }
        if (missing.isNotEmpty()) throw MigrationsNotAppliedException(module, missing)
    }

    /** Runs Flyway once: the versions applied, with how long each took in milliseconds. */
    @Suppress("SpreadOperator") // Flyway takes its migrations as varargs: one copy of a module's few, per pass.
    private fun pass(table: String, migrations: List<SchemaMigration>): List<Pair<String, Int>> {
        val flyway = Flyway.configure()
            .dataSource(source)
            .table(table)
            .javaMigrations(*migrations.map(::ModuleMigration).toTypedArray())
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            // No quote in the description: Flyway writes it into its insert as it is.
            .baselineDescription("before the first migration")
            .load()
        return flyway.migrate().migrations.map { it.version to it.executionTime }
    }

    private fun history(connection: Connection, table: String): List<Applied> =
        if (!connection.exists(table)) {
            emptyList()
        } else {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version, checksum, success FROM $table WHERE version IS NOT NULL")
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    Applied(
                                        version = rows.getString("version").toLong(),
                                        checksum = rows.getInt("checksum"),
                                        success = rows.getBoolean("success"),
                                    ),
                                )
                            }
                        }
                    }
            }
        }

    private class Applied(val version: Long, val checksum: Int, val success: Boolean)

    /** One [SchemaMigration] as Flyway sees it: its version, its name, the CRC32 of its SQL. */
    private class ModuleMigration(private val migration: SchemaMigration) : JavaMigration {
        override fun getVersion(): MigrationVersion = MigrationVersion.fromVersion(migration.version.toString())

        override fun getDescription(): String = migration.name

        override fun getChecksum(): Int = checksum(migration)

        override fun canExecuteInTransaction(): Boolean = true

        override fun migrate(context: Context) {
            context.connection.run(migration.sql)
        }
    }

    /** The settings of a migration's connection. */
    companion object {
        /** How many times a pass runs by default when it keeps waiting too long for a lock. */
        const val LOCK_ATTEMPTS = 5

        /** The SQLSTATE of a statement that waited longer than `lock_timeout` (`lock_not_available`). */
        private const val LOCK_NOT_AVAILABLE = "55P03"

        /**
         * The JDBC URL of the migrating connection: the CRL timeouts of a connection, but no statement or socket
         * timeout (a migration may rewrite a large table) and a lock wait of 3 s (CRL-05), so that a migration behind
         * a long transaction fails and is run again instead of blocking every query queued behind its lock.
         */
        fun migrationUrl(config: DatabaseConfig): String =
            JdbcDatabase.jdbcUrl(config, listOf("lock_timeout=3s"), socketTimeoutSeconds = 0)

        private fun checksum(migration: SchemaMigration): Int =
            CRC32().apply { update(migration.sql.toByteArray(Charsets.UTF_8)) }.value.toInt()

        private fun FlywayException.isLockTimeout(): Boolean =
            generateSequence<Throwable>(this) { it.cause }
                .any { it is SQLException && it.sqlState == LOCK_NOT_AVAILABLE }

        private fun Connection.run(sql: String) {
            createStatement().use { it.execute(sql) }
        }

        private fun Connection.exists(table: String): Boolean =
            prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getBoolean(1)
                }
            }

        private fun historyTable(module: String): String = "flyway_" + moduleKey(module)
    }
}
