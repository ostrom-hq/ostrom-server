// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.config.DatabaseConfig
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.postgresql.ds.PGSimpleDataSource

/**
 * `ostrom-server migrate` on Flyway: every module's migrations, in module order, with the owner's credentials, while
 * one connection holds the PostgreSQL advisory lock [MIGRATION_LOCK_KEY]. Init containers that migrate at the same
 * time wait for each other instead of racing on the same versions, and the lock is released when the pass ends, on
 * failure and cancellation too. The lock's own wait has no `lock_timeout`: only the migrations' statements have one.
 *
 * @param config The owner's database (`OSTROM_DATABASE_MIGRATION_*`).
 * @param logger Where applied migrations are reported.
 * @param modules The modules, in order.
 * @param runner Applies one module's migrations; Flyway on [config] by default.
 */
suspend fun migrateModules(
    config: DatabaseConfig,
    logger: Logger,
    modules: List<ServerModule>,
    runner: MigrationRunner = FlywayMigrationRunner(migrationSource(config), logger),
) {
    val lock = withContext(Dispatchers.IO) { migrationSource(config).connection }
    try {
        withContext(Dispatchers.IO) {
            lock.createStatement().use { statement ->
                statement.execute("SET lock_timeout = 0")
                statement.execute("SELECT pg_advisory_lock($MIGRATION_LOCK_KEY)")
            }
        }
        modules.forEach { runner.migrate(it.name, it.migrations) }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { lock.close() }
    }
}

/** The owner's connections for migrations, without a pool: Flyway opens the few it needs. */
internal fun migrationSource(config: DatabaseConfig): PGSimpleDataSource = PGSimpleDataSource().apply {
    setURL(FlywayMigrationRunner.migrationUrl(config))
    user = config.username
    password = config.password
}
