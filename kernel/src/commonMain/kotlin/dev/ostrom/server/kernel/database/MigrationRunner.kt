// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import dev.ostrom.server.kernel.logging.Logger
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

/**
 * Applies the migrations of each module, in the order of the modules, each module with its own history table
 * (`_migrations_<module>`) so that two modules never compete for a version number.
 */
interface MigrationRunner {
    /**
     * Applies the migrations a module has not applied yet, and verifies the checksums of those it has.
     *
     * @param module The module name (lower-case letters, digits and `-`).
     * @param migrations The module's migrations.
     *
     * @throws io.github.smyrgeorge.sqlx4k.SQLError When a migration fails or an applied one was edited.
     */
    suspend fun migrate(module: String, migrations: List<SchemaMigration>)
}

/**
 * [MigrationRunner] on sqlx4k's migrator: each module's migrations run in one transaction per file.
 *
 * Several replicas starting at once are not serialized yet: start one replica first after a release that adds
 * migrations (docs/MODULES.md, limits).
 *
 * @param driver The database.
 * @param logger Where applied migrations are reported.
 */
class MigrationRunnerImpl(
    private val driver: Driver,
    private val logger: Logger,
) : MigrationRunner {
    override suspend fun migrate(module: String, migrations: List<SchemaMigration>) {
        require(MODULE_NAME.matches(module)) { "Module name must match $MODULE_NAME." }
        if (migrations.isEmpty()) return
        val table = "_migrations_" + module.replace('-', '_')
        val files = migrations.map { MigrationFile("${it.version}_${it.name}.sql", it.sql) }
        val results = driver.migrate(
            files = files,
            table = table,
            afterFileMigration = { migration, duration ->
                logger.info(
                    "migration applied",
                    mapOf(
                        "module" to module,
                        "version" to migration.version,
                        "duration_ms" to duration.inWholeMilliseconds,
                    ),
                )
            },
        ).getOrThrow()
        logger.info(
            "migrations checked",
            mapOf("module" to module, "total" to results.total, "applied" to results.applied),
        )
    }
}

// No `_`: `a-b` and `a_b` would share the table `_migrations_a_b`.
private val MODULE_NAME = Regex("[a-z][a-z0-9-]{0,40}")
