// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.config.DatabaseConfig
import run.ostrom.server.kernel.logging.Logger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URLEncoder
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.DatabaseConfig as ExposedConfig
import kotlin.uuid.Uuid

/**
 * Exposed transactions over a bounded HikariCP pool, as TeamCode's `Database` (docs/decisions/0001, step 2), with the
 * settings of `.parity/audit/controlresell-lessons.md` (CRL-01, CRL-02, CRL-03).
 *
 * The only way in is [query], whose block is not `suspend`: no network call can run inside a transaction, and
 * `idle_in_transaction_session_timeout` ends one that waits anyway. A transaction is never replayed by Exposed
 * (`defaultMaxAttempts = 1`): a retry is a named, idempotent call site's decision. Exposed's statement log is SLF4J's
 * debug level, which the server never enables (logback comes with step 3, its `Exposed` logger off as TeamCode's).
 *
 * @param config How to reach PostgreSQL, and as whom.
 * @param poolSize The pool's connections, 2 to 64.
 */
class JdbcDatabase(config: DatabaseConfig, poolSize: Int) : AutoCloseable {
    private val source = HikariDataSource(hikariConfig(config, poolSize))
    private val exposed = Database.connect(
        source,
        databaseConfig = ExposedConfig {
            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            defaultMaxAttempts = 1
        },
    )

    /**
     * Runs [block] in one transaction, on the IO dispatcher.
     *
     * @return What [block] returns, after the commit.
     */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(exposed) { block() }
    }

    /**
     * Runs [block] in one transaction confined to one organization, as the sqlx4k [inOrg]: the transaction switches to
     * [TENANT_ROLE] and sets [TENANT_SETTING] for itself alone (`SET LOCAL`, `set_config(…, true)`), so a tenant
     * table's row-level security policy only shows and accepts the rows of [orgId], whatever the query says. Both end
     * with the transaction: the pooled connection goes back with neither.
     *
     * The queries still filter on `org_id` themselves: the policy is the second wall until a later step drops
     * row-level security for typed org filters (SD-08).
     *
     * @param orgId The organization the queries are confined to.
     * @param block The queries.
     *
     * @return What [block] returns, after the commit.
     */
    suspend fun <T> inOrg(orgId: Uuid, block: JdbcTransaction.() -> T): T = query {
        exec("SET LOCAL ROLE $TENANT_ROLE")
        exec("SELECT set_config('$TENANT_SETTING', ?, true)", listOf(TextColumnType() to orgId.toString())) {}
        block()
    }

    /** The pool's state for `/ready` ([PoolHealthCheck]), read without opening a connection. */
    fun state(): PoolState = PoolState(
        open = source.isRunning,
        waiting = source.hikariPoolMXBean.threadsAwaitingConnection,
    )

    /**
     * Reads the modules' migration histories through this pool, to check at start that every migration is applied.
     *
     * @param logger Where the runner reports.
     */
    fun migrationRunner(logger: Logger): MigrationRunner = FlywayMigrationRunner(source, logger)

    override fun close() {
        TransactionManager.closeAndUnregister(exposed)
        source.close()
    }

    /** The bounds and the settings of the pool. */
    companion object {
        /** The fewest connections a pool may have. */
        const val MIN_POOL_SIZE = 2

        /** The most connections a pool may have, so that replicas × pool stays under `max_connections` (CRL-01). */
        const val MAX_POOL_SIZE = 64

        private const val CONNECTION_TIMEOUT_MILLIS = 5_000L
        private const val VALIDATION_TIMEOUT_MILLIS = 3_000L
        private const val IDLE_TIMEOUT_MILLIS = 300_000L
        private const val MAX_LIFETIME_MILLIS = 1_800_000L
        private const val KEEPALIVE_MILLIS = 600_000L
        private const val LEAK_DETECTION_MILLIS = 60_000L
        private const val SOCKET_TIMEOUT_SECONDS = 15
        private val SERVING_SESSION = listOf(
            "statement_timeout=10s",
            "lock_timeout=5s",
            "idle_in_transaction_session_timeout=15s",
        )

        /**
         * The JDBC URL of [config]: pgjdbc's connect, login and socket timeouts (seconds, the socket's a backstop above
         * `statement_timeout`), TCP keepalive, the application name, batched inserts rewritten, and the session's
         * statement, lock and idle-in-transaction timeouts enforced by the server (CRL-02).
         *
         * @param session The session's settings (`name=value`), the serving connection's by default.
         * @param socketTimeoutSeconds How long a read may wait on the socket, 0 for no limit.
         */
        internal fun jdbcUrl(
            config: DatabaseConfig,
            session: List<String> = SERVING_SESSION,
            socketTimeoutSeconds: Int = SOCKET_TIMEOUT_SECONDS,
        ): String {
            val settings = session.joinToString(" ") { "-c $it" }
            val options = listOf(
                "connectTimeout=5",
                "loginTimeout=5",
                "socketTimeout=$socketTimeoutSeconds",
                "tcpKeepAlive=true",
                "ApplicationName=ostrom-server",
                "reWriteBatchedInserts=true",
                "options=" + URLEncoder.encode(settings, Charsets.UTF_8).replace("+", "%20"),
            )
            val location = config.url.substringAfter("://")
            return "jdbc:postgresql://$location?" + options.joinToString("&")
        }

        /**
         * The pool's settings (CRL-01): bounded, a short wait for a connection, autocommit off, READ COMMITTED.
         *
         * @throws IllegalArgumentException When [poolSize] is outside [MIN_POOL_SIZE]..[MAX_POOL_SIZE].
         */
        internal fun hikariConfig(config: DatabaseConfig, poolSize: Int): HikariConfig {
            require(poolSize in MIN_POOL_SIZE..MAX_POOL_SIZE) {
                "The database pool size must be between $MIN_POOL_SIZE and $MAX_POOL_SIZE, not $poolSize."
            }
            return HikariConfig().apply {
                jdbcUrl = jdbcUrl(config)
                username = config.username
                password = config.password
                poolName = "ostrom-server"
                maximumPoolSize = poolSize
                minimumIdle = 1
                connectionTimeout = CONNECTION_TIMEOUT_MILLIS
                validationTimeout = VALIDATION_TIMEOUT_MILLIS
                idleTimeout = IDLE_TIMEOUT_MILLIS
                maxLifetime = MAX_LIFETIME_MILLIS
                keepaliveTime = KEEPALIVE_MILLIS
                leakDetectionThreshold = LEAK_DETECTION_MILLIS
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            }
        }
    }
}
