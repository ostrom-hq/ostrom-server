// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.logging.Logger
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
        val pass = driver.migrate(
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
        )
        // Even after a failed pass: the history may have been created, and must not stay writable meanwhile.
        val locked = driver.execute(ownerOnlySql(table))
        val results = pass.getOrThrow()
        locked.getOrThrow()
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

    private fun historyTable(module: String): String = "_migrations_" + moduleKey(module)

    private fun List<SchemaMigration>.files() = map { MigrationFile("${it.version}_${it.name}.sql", it.sql) }
}

/**
 * The part of a module's history table names that comes from the module's name.
 *
 * @throws IllegalArgumentException When [module] is not lower-case letters, digits and `-`.
 */
internal fun moduleKey(module: String): String {
    require(MODULE_NAME.matches(module)) { "Module name must match $MODULE_NAME." }
    return module.replace('-', '_')
}

/**
 * Refuses a module's migrations whose versions are not positive and increasing (TeamCode 3da27ca0): two migrations
 * with one version would make Flyway, or sqlx4k, apply one of them only.
 *
 * @throws IllegalArgumentException When a version is below 1, repeated or out of order.
 */
internal fun requireVersions(module: String, migrations: List<SchemaMigration>) {
    val versions = migrations.map { it.version }
    require(versions.zipWithNext().all { (a, b) -> a < b } && versions.all { it > 0 }) {
        "The migrations of module $module must have increasing versions from 1, not $versions."
    }
}

/**
 * Only the migrator writes a module's history. A deployment's default privileges (`ci/database-roles.sql`, the Helm
 * chart) give the serving role SELECT, INSERT, UPDATE and DELETE on every table of the owner, the history tables
 * included: the serving process could erase the trace of a migration, which the next `migrate` would then replay
 * or fail on. Every role but the owner that holds more than SELECT (on the table or on a column, MAINTAIN
 * included) loses all of it, with what it granted on (CASCADE), and gets SELECT back if it had it:
 * [MigrationRunner.verify] reads the history at start. At every pass, after a failed one too, so a database migrated
 * before keeps no write either. Skipped when the connected role is not (a member of) the table's owner, which alone
 * may revoke.
 */
internal fun ownerOnlySql(table: String): String = """
    DO $$
    DECLARE r record;
    BEGIN
      FOR r IN
        SELECT CASE WHEN g.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(g.grantee)) END AS name,
          bool_or(g.privilege_type = 'SELECT' AND NOT g.on_column) AS reads
        FROM pg_class c
        CROSS JOIN LATERAL (
          SELECT a.grantee, a.privilege_type, false AS on_column FROM aclexplode(c.relacl) a
          UNION ALL
          SELECT a.grantee, a.privilege_type, true FROM pg_attribute t, aclexplode(t.attacl) a
          WHERE t.attrelid = c.oid
        ) g
        WHERE c.oid = to_regclass('$table') AND g.grantee <> c.relowner
          AND pg_has_role(current_user, c.relowner, 'USAGE')
        GROUP BY g.grantee
        HAVING bool_or(g.privilege_type <> 'SELECT' OR g.on_column)
      LOOP
        EXECUTE format('REVOKE ALL ON %I FROM %s CASCADE', '$table', r.name);
        IF r.reads THEN
          EXECUTE format('GRANT SELECT ON %I TO %s', '$table', r.name);
        END IF;
      END LOOP;
    END
    $$
""".trimIndent()

// No `_`: `a-b` and `a_b` would share the table `_migrations_a_b`.
private val MODULE_NAME = Regex("[a-z][a-z0-9-]{0,40}")
