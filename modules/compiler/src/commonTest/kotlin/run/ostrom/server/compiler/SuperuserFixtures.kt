// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

import run.ostrom.server.testing.TestServer
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

// The tests' fixtures and probes as the superuser (no row-level security, every table), on its JDBC pool.

/** Runs [sql] as the superuser, a `?` for each of [args] (a bulk fixture, say from `generate_series`). */
internal suspend fun TestServer.superuserSql(sql: String, args: List<Pair<IColumnType<*>, Any?>> = emptyList()) {
    database.superuserJdbc.query { exec(sql, args) }
}

/** Runs [block] in one transaction as the superuser. */
internal suspend fun <T> TestServer.asSuperuser(block: JdbcTransaction.() -> T): T = database.superuserJdbc.query(block)

/** Runs `VACUUM ANALYZE` on each of [tables] (outside a transaction block, as it must). */
internal suspend fun TestServer.vacuumAnalyze(vararg tables: String) {
    database.superuserOutsideTransaction(*tables.map { "VACUUM ANALYZE $it" }.toTypedArray())
}

/** The first column of every row [sql] returns, as text. */
internal fun JdbcTransaction.texts(sql: String): List<String> =
    exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
        buildList { while (rs.next()) add(rs.getString(1)) }
    }.orEmpty()
