// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import dev.ostrom.server.kernel.logging.Logger
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

/**
 * Applies the migrations of each module, in the order of the modules, each module with its own history table
 * (`_migrations_<module>`) so that two modules never compete for a version number.
 */
interface MigrationRunner {
    /**
     * Applies the migrations a module has not applied yet, and verifies the checksums of those it has. Then the history
     * table is left writable by its owner alone (see [MigrationRunnerImpl]).
     *
     * @param module The module name (lower-case letters, digits and `-`).
     * @param migrations The module's migrations.
     *
     * @throws io.github.smyrgeorge.sqlx4k.SQLError When a migration fails or an applied one was edited.
     */
    suspend fun migrate(module: String, migrations: List<SchemaMigration>)

    /**
     * Checks, without changing anything, that every migration of a module is applied unchanged: the serving process
     * runs this at start, since only `ostrom-server migrate` (owner's credentials) applies migrations.
     *
     * @param module The module name.
     * @param migrations The module's migrations.
     *
     * @throws MigrationsNotAppliedException When a migration is missing or differs from the applied one.
     */
    suspend fun verify(module: String, migrations: List<SchemaMigration>)
}

/**
 * The database is behind the code (or an applied migration was edited): the server does not serve until
 * `ostrom-server migrate` has run.
 *
 * @param module The module.
 * @param versions The versions missing or different.
 */
class MigrationsNotAppliedException(module: String, versions: List<Long>) : IllegalStateException(
    "Migrations $versions of module $module are not applied, or differ from the applied ones: run " +
        "`ostrom-server migrate`.",
)

/**
 * [MigrationRunner] on sqlx4k's migrator: each module's migrations run in one transaction per file. Concurrent
 * passes are serialized by [migrateUnderLock].
 *
 * @param driver The database.
 * @param logger Where applied migrations are reported.
 */
class MigrationRunnerImpl(
    private val driver: Driver,
    private val logger: Logger,
) : MigrationRunner {
    override suspend fun migrate(module: String, migrations: List<SchemaMigration>) {
        val table = historyTable(module)
        if (migrations.isEmpty()) return
        val results = driver.migrate(
            files = migrations.files(),
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
        driver.execute(ownerOnly(table)).getOrThrow()
        logger.info(
            "migrations checked",
            mapOf("module" to module, "total" to results.total, "applied" to results.applied),
        )
    }

    override suspend fun verify(module: String, migrations: List<SchemaMigration>) {
        val table = historyTable(module)
        if (migrations.isEmpty()) return
        val lookup = Statement.create("SELECT CAST(to_regclass(:table) AS text) AS name").bind("table", table)
        val present = driver.fetchAll(lookup)
            .getOrThrow()
            .single()
            .get("name")
            .asStringOrNull()
        val applied = if (present == null) {
            emptyMap()
        } else {
            driver.fetchAll("SELECT version, checksum FROM $table").getOrThrow()
                .associate { it.get("version").asLong() to it.get("checksum").asString() }
        }
        val missing = migrations.files().filter { applied[it.version] != it.checksum }.map { it.version }
        if (missing.isNotEmpty()) throw MigrationsNotAppliedException(module, missing)
    }

    /**
     * Only the migrator writes a module's history. A deployment's default privileges (`ci/database-roles.sql`, the Helm
     * chart) give the serving role SELECT, INSERT, UPDATE and DELETE on every table of the owner, the history tables
     * included: the serving process could erase the trace of a migration, which the next `migrate` would then replay
     * or fail on. Every privilege but SELECT is revoked from every role but the owner, at every pass, so a database
     * migrated before this keeps no write either. The serving role keeps SELECT: [verify] reads the history at start.
     * Grants made through another role are recorded as the owner's, which may therefore revoke them all.
     */
    private fun ownerOnly(table: String): String = """
        DO $$
        DECLARE grantee text;
        BEGIN
          FOR grantee IN
            SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END
            FROM pg_class c, aclexplode(c.relacl) a
            WHERE c.oid = to_regclass('$table') AND a.grantee <> c.relowner AND a.privilege_type <> 'SELECT'
          LOOP
            EXECUTE format(
              'REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON %I FROM %s', '$table', grantee
            );
          END LOOP;
        END
        $$
    """.trimIndent()

    private fun historyTable(module: String): String {
        require(MODULE_NAME.matches(module)) { "Module name must match $MODULE_NAME." }
        return "_migrations_" + module.replace('-', '_')
    }

    private fun List<SchemaMigration>.files() = map { MigrationFile("${it.version}_${it.name}.sql", it.sql) }
}

// No `_`: `a-b` and `a_b` would share the table `_migrations_a_b`.
private val MODULE_NAME = Regex("[a-z][a-z0-9-]{0,40}")
