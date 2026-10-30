package dev.ostrom.server.kernel.database

import dev.ostrom.server.kernel.config.DatabaseConfig
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL

/**
 * Opens the PostgreSQL connection pool. Repositories receive the returned [Driver] (sqlx4k), the same interface
 * a SQLite driver implements, so the local and test modes can swap the database without touching a repository.
 *
 * @param config How to reach PostgreSQL.
 *
 * @return The connection pool; close it when the server stops.
 */
fun connectPostgres(config: DatabaseConfig): Driver = postgreSQL(
    url = config.url,
    username = config.username,
    password = config.password,
    options = ConnectionPool.Options(maxConnections = config.maxConnections),
)
