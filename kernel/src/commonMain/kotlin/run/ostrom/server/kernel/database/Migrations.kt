// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.module.ServerModule
import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The PostgreSQL advisory lock `ostrom-server migrate` holds: the bytes of "ostrom". */
internal const val MIGRATION_LOCK_KEY = 0x6F7374726F6DL

/**
 * Applies every module's migrations, in module order, while holding a PostgreSQL advisory lock on one pinned
 * connection: replicas or init containers that migrate at the same time wait for each other instead of racing on
 * the same versions. The lock is released when the pass ends, on failure and cancellation too.
 *
 * @param runner The migration runner, on the owner's connection.
 * @param modules The modules, in order.
 */
suspend fun Driver.migrateUnderLock(runner: MigrationRunner, modules: List<ServerModule>) {
    val lock = acquire().getOrThrow()
    try {
        lock.fetchAll("SELECT pg_advisory_lock($MIGRATION_LOCK_KEY)").getOrThrow()
        modules.forEach { runner.migrate(it.name, it.migrations) }
    } finally {
        withContext(NonCancellable) {
            lock.fetchAll("SELECT pg_advisory_unlock($MIGRATION_LOCK_KEY)")
            lock.close()
        }
    }
}
