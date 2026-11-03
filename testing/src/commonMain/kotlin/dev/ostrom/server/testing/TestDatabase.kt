// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.DatabaseConfig
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.TENANT_ROLE
import dev.ostrom.server.kernel.database.connectPostgres
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * A PostgreSQL database created for one test and dropped after it, so tests never see each other's rows, with the
 * roles of a deployment (`ci/database-roles.sql`, docs/tenancy.md): the tests and the server run exactly as in
 * production, never as a role that bypasses row-level security.
 *
 * - [owner]: the owner of the database, the schema and every table, `NOSUPERUSER NOBYPASSRLS CREATEROLE`, admin of
 *   `ostrom_tenant` and `ostrom_router`. It runs the migrations, like `ostrom-server migrate`.
 * - [driver]: the serving role, `NOSUPERUSER NOBYPASSRLS`, owner of nothing, member of `ostrom_tenant`, with the rows
 *   of the owner's tables by default privileges. Every test and the test server go through it.
 * - [superuser]: the configured user (CI's PostgreSQL service user), which creates the database and the roles. Only for
 *   what no deployment does: creating roles to test the start check, reading what a test must prove is stored.
 *
 * The owner and serving roles are created for this database alone (their attributes are those of
 * `ci/database-roles.sql`, their names unique so tests in parallel never share them) and dropped with it;
 * `ostrom_tenant` and `ostrom_router`, global to the cluster and named by the migrations, are created when missing.
 *
 * Configured by `OSTROM_TEST_DATABASE_URL`, an existing database to connect to with its credentials
 * (`postgresql://ostrom:ostrom@localhost:5432/ostrom_test`), or `OSTROM_TEST_DATABASE_USER` and
 * `OSTROM_TEST_DATABASE_PASSWORD`; that user must be a superuser (it hands the schema over and creates roles that a
 * `CREATEROLE` role could not, `BYPASSRLS` ones included), like CI's PostgreSQL service user. When the URL is unset the
 * database tests are skipped, unless `OSTROM_TEST_DATABASE_REQUIRED=true` (set in CI), which makes them fail instead:
 * CI never passes by skipping them.
 *
 * @property driver The connection pool of the serving role.
 * @property owner The connection pool of the owner, for migrations.
 * @property superuser The connection pool of the configured user, never the path under test.
 */
class TestDatabase private constructor(
    val driver: Driver,
    val owner: Driver,
    val superuser: Driver,
    private val admin: DatabaseConfig,
    private val id: String,
) {
    private val name: String get() = databaseName(id)
    private val url: String get() = admin.url.substringBeforeLast('/') + "/" + name

    /** The name of the owner role, which owns the database, its schema and its tables. */
    val ownerRole: String get() = ownerRoleName(id)

    /** The name of the serving role, the one [driver] connects as. */
    val servingRole: String get() = servingRoleName(id)

    /**
     * Runs [block] connected to this database as a fresh login role, like a deployment's serving role: not a
     * superuser, owning nothing, granted only the use of the `public` schema (grant it the tables it needs). With
     * [bypassRowLevelSecurity] the role has `BYPASSRLS`, the case the server refuses. The role is dropped afterwards.
     *
     * A pool larger than [ROLE_POOL_SIZE] (a read bench's 20 concurrent callers, a load test's contended pool) runs
     * [exclusively]: one at a time on the PostgreSQL server, whatever test process opens it. The wait comes before the
     * pool opens, so a bench never times it.
     *
     * @param bypassRowLevelSecurity Whether the role bypasses row-level security.
     * @param poolSize The connections of the role's pool; 1 makes every query run on the same session.
     * @param block The test, given the role's name and a pool connected as the role.
     *
     * @return What [block] returns.
     */
    suspend fun <T> withRole(
        bypassRowLevelSecurity: Boolean = false,
        poolSize: Int = ROLE_POOL_SIZE,
        block: suspend (role: String, driver: Driver) -> T,
    ): T = if (poolSize > ROLE_POOL_SIZE) {
        exclusively { connectedAsRole(bypassRowLevelSecurity, poolSize, block) }
    } else {
        connectedAsRole(bypassRowLevelSecurity, poolSize, block)
    }

    /**
     * Runs [block] alone among the test processes that share this PostgreSQL server: it holds the server's lock of the
     * timed and large phases, an advisory lock taken in the configured database (the same for every test database) on
     * a connection of its own, released afterwards, on failure and cancellation too, and by PostgreSQL if the process
     * dies with it. The lock is tried every [LOCK_RETRY], never waited on in PostgreSQL, so that a test cancelled
     * while it waits stops at once. Within [block] and its child coroutines, this database takes it again at once (a
     * bench's large pool inside its timing); another coroutine waits like another process.
     *
     * CI's PostgreSQL service has `max_connections` 100 and Gradle runs the modules' tests in parallel, each test
     * with pools of its own (up to 12 connections, [create]). Two read benches of 20 callers and the launch path's
     * pool of 50 at once ran out of connections (« sorry, too many clients already », on main and on pull requests,
     * 03/11/2026), and two benches timing at once on the runner's few cores measured each other: a route's p95 went
     * over its budget on a pull request that never touched it (ReportsReadsBenchTest, server#282).
     *
     * @param block What runs alone.
     * @return What [block] returns.
     */
    suspend fun <T> exclusively(block: suspend () -> T): T = exclusively(onWait = {}, block)

    /**
     * [exclusively], calling [onWait] each time the lock is found held by another.
     *
     * @param onWait Called on each refused try.
     * @param block What runs alone.
     * @return What [block] returns.
     */
    internal suspend fun <T> exclusively(onWait: () -> Unit, block: suspend () -> T): T {
        if (currentCoroutineContext()[Holder]?.database === this) return block()
        val pool = connectPostgres(admin)
        try {
            val lock = pool.acquire().getOrThrow()
            try {
                while (!lock.fetchAll(TRY_LOCK).getOrThrow().single().get("held").asBoolean()) {
                    onWait()
                    // Real time, also under a test dispatcher's virtual clock.
                    withContext(Dispatchers.Default) { delay(LOCK_RETRY) }
                }
                return withContext(Holder(this)) { block() }
            } finally {
                withContext(NonCancellable) {
                    lock.fetchAll("SELECT pg_advisory_unlock($EXCLUSIVE_LOCK)")
                    lock.close()
                }
            }
        } finally {
            withContext(NonCancellable) { pool.close() }
        }
    }

    // The coroutines that run within exclusively for this database, the one that holds the lock and its children.
    private class Holder(val database: TestDatabase) : AbstractCoroutineContextElement(Holder) {
        companion object Key : CoroutineContext.Key<Holder>
    }

    private suspend fun <T> connectedAsRole(
        bypassRowLevelSecurity: Boolean,
        poolSize: Int,
        block: suspend (role: String, driver: Driver) -> T,
    ): T {
        val role = "ostrom_test_role_" + Uuid.random().toHexString()
        val password = Uuid.random().toHexString()
        val bypass = if (bypassRowLevelSecurity) "BYPASSRLS" else "NOBYPASSRLS"
        superuser.execute(
            "CREATE ROLE $role LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION $bypass PASSWORD '$password'",
        ).getOrThrow()
        try {
            superuser.execute("GRANT USAGE ON SCHEMA public TO $role").getOrThrow()
            val connected = connectPostgres(DatabaseConfig(url, role, password, poolSize))
            try {
                return block(role, connected)
            } finally {
                connected.close()
            }
        } finally {
            superuser.execute("DROP OWNED BY $role").getOrThrow()
            superuser.execute("DROP ROLE $role").getOrThrow()
        }
    }

    /** Closes the pools, drops the database and its owner and serving roles. */
    suspend fun close() {
        try {
            driver.close()
            owner.close()
            superuser.close()
        } finally {
            administer(
                admin,
                "DROP DATABASE IF EXISTS $name WITH (FORCE)",
                "DROP OWNED BY $servingRole, $ownerRole",
                "DROP ROLE $servingRole",
                "DROP ROLE $ownerRole",
            )
        }
    }

    /** Creating test databases. */
    companion object {
        private const val POOL_SIZE = 4
        // KernelPostgresTest runs three migration passes at once, each pinning a connection for its lock, plus the
        // connections their migrations use: a pool of 2 deadlocked.
        private const val OWNER_POOL_SIZE = 6
        private const val SUPERUSER_POOL_SIZE = 2
        // The pool of withRole by default, the largest one that never waits for the others.
        private const val ROLE_POOL_SIZE = 2

        /** The advisory lock key of [exclusively], in the configured database. */
        internal const val EXCLUSIVE_LOCK = 774_210_001L
        private const val TRY_LOCK = "SELECT pg_try_advisory_lock($EXCLUSIVE_LOCK) AS held"
        private val LOCK_RETRY = 200.milliseconds

        /**
         * Creates a fresh database with its owner and serving roles.
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
            val id = Uuid.random().toHexString()
            val name = databaseName(id)
            val ownerRole = ownerRoleName(id)
            val servingRole = servingRoleName(id)
            val ownerPassword = Uuid.random().toHexString()
            val servingPassword = Uuid.random().toHexString()
            // As ci/database-roles.sql, the roles first, then the database owned by the owner.
            administer(
                config,
                tolerated("CREATE ROLE $TENANT NOLOGIN NOSUPERUSER NOBYPASSRLS"),
                tolerated("CREATE ROLE $ROUTER NOLOGIN NOSUPERUSER NOBYPASSRLS"),
                "CREATE ROLE $ownerRole LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS " +
                    "PASSWORD '$ownerPassword'",
                "CREATE ROLE $servingRole LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS " +
                    "PASSWORD '$servingPassword'",
                "GRANT $TENANT TO $ownerRole WITH ADMIN OPTION",
                "GRANT $TENANT TO $servingRole",
                "GRANT $ROUTER TO $ownerRole WITH ADMIN OPTION",
                "CREATE DATABASE $name OWNER $ownerRole",
            )
            val testUrl = config.url.substringBeforeLast('/') + "/" + name
            val superuser = connectPostgres(config.copy(url = testUrl, maxConnections = SUPERUSER_POOL_SIZE))
            superuser.grantAsADeployment(ownerRole, servingRole)
            return TestDatabase(
                driver = connectPostgres(DatabaseConfig(testUrl, servingRole, servingPassword, POOL_SIZE)),
                owner = connectPostgres(DatabaseConfig(testUrl, ownerRole, ownerPassword, OWNER_POOL_SIZE)),
                superuser = superuser,
                admin = config,
                id = id,
            )
        }

        private const val TENANT = TENANT_ROLE

        private fun databaseName(id: String) = "ostrom_test_$id"

        private fun ownerRoleName(id: String) = "ostrom_test_owner_$id"

        private fun servingRoleName(id: String) = "ostrom_test_app_$id"

        // The owner of the cross-organization functions (docs/tenancy.md), never granted to the serving role.
        private const val ROUTER = "ostrom_router"

        // Roles are global to the cluster: tolerate one created by another test, a migration or the roles script.
        private fun tolerated(sql: String) =
            "DO $$ BEGIN $sql; EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END $$"

        /** The schema's owner and grants, as ci/database-roles.sql sets them on a deployment's database. */
        private suspend fun Driver.grantAsADeployment(ownerRole: String, servingRole: String) {
            listOf(
                "ALTER SCHEMA public OWNER TO $ownerRole",
                "REVOKE ALL ON SCHEMA public FROM PUBLIC",
                "GRANT USAGE ON SCHEMA public TO $servingRole, $TENANT",
                "ALTER DEFAULT PRIVILEGES FOR ROLE $ownerRole IN SCHEMA public " +
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $servingRole",
                "ALTER DEFAULT PRIVILEGES FOR ROLE $ownerRole IN SCHEMA public " +
                    "GRANT USAGE, SELECT ON SEQUENCES TO $servingRole",
                "ALTER DEFAULT PRIVILEGES FOR ROLE $ownerRole IN SCHEMA public " +
                    "GRANT EXECUTE ON FUNCTIONS TO $servingRole",
            ).forEach { execute(it).getOrThrow() }
        }

        /** Runs [statements] on a connection to the configured database opened for them alone, always closed. */
        private suspend fun administer(config: DatabaseConfig, vararg statements: String) {
            val admin = connectPostgres(config)
            try {
                statements.forEach { admin.execute(it).getOrThrow() }
            } finally {
                admin.close()
            }
        }
    }
}
