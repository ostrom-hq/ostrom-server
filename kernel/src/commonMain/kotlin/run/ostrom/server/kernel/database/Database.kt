// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.config.DatabaseConfig
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlin.time.Duration.Companion.seconds

/**
 * Opens the PostgreSQL connection pool. Repositories receive the returned [Driver] (sqlx4k), the same interface
 * a SQLite driver implements, so the local and test modes can swap the database without touching a repository.
 * A caller cancelled while it waits for a connection ends cancelled, never with a database error
 * ([CancellationKeepingDriver]).
 *
 * A connection idle for [DRIVER_IDLE_TIMEOUT] is closed rather than handed out: the pool validates nothing, and since
 * most modules moved to the JDBC pool (JVM step 5) no background pass drains it, so the connections that sat idle
 * through a database outage would otherwise fail the first requests once it is back (`ci/replicas-smoke.sh`, step 5).
 *
 * @param config How to reach PostgreSQL.
 *
 * @return The connection pool; close it when the server stops.
 */
fun connectPostgres(config: DatabaseConfig): Driver = CancellationKeepingDriver(
    postgreSQL(
        url = config.url,
        username = config.username,
        password = config.password,
        options = ConnectionPool.Options(maxConnections = config.maxConnections, idleTimeout = DRIVER_IDLE_TIMEOUT),
    ),
)

/** How long a connection of the sqlx4k pool may sit idle before it is closed instead of reused. */
private val DRIVER_IDLE_TIMEOUT = 10.seconds
