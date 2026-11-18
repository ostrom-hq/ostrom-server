// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

/**
 * Applies the migrations of each module, in the order of the modules, each module with its own history table
 * (`flyway_<module>`, [FlywayMigrationRunner]) so that two modules never compete for a version number.
 */
interface MigrationRunner {
    /**
     * Applies the migrations a module has not applied yet, and verifies the checksums of those it has. Then the history
     * table is left writable by its owner alone ([ownerOnlySql]).
     *
     * @param module The module name (lower-case letters, digits and `-`).
     * @param migrations The module's migrations.
     *
     * @throws MigrationsNotAppliedException When an applied migration was edited.
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
 * The part of a module's history table names that comes from the module's name.
 *
 * @throws IllegalArgumentException When [module] is not lower-case letters, digits and `-`.
 */
internal fun moduleKey(module: String): String {
    require(MODULE_NAME.matches(module)) { "Module name must match $MODULE_NAME." }
    return module.replace('-', '_')
}

/**
 * Refuses a module's migrations whose versions are not 1, 2, 3… in order (TeamCode 3da27ca0): two migrations with one
 * version would make Flyway apply one of them only, and a gap would be skipped without a word.
 *
 * @throws IllegalArgumentException When a version is repeated, out of order or missing.
 */
internal fun requireVersions(module: String, migrations: List<SchemaMigration>) {
    val versions = migrations.map { it.version }
    require(versions == (1L..versions.size).toList()) {
        "The migrations of module $module must be numbered 1, 2, 3… without a gap or a repeat, not $versions."
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

// No `_`: `a-b` and `a_b` would share the table `flyway_a_b`.
private val MODULE_NAME = Regex("[a-z][a-z0-9-]{0,40}")
