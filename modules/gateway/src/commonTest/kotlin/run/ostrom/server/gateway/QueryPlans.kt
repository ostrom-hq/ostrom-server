// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.explain

/**
 * The plan of a repository's statement, as Exposed sends it, under `EXPLAIN (ANALYZE, BUFFERS)` in this transaction: a
 * write runs, so a caller that explains several rolls each back.
 *
 * @param statement The statement.
 *
 * @return The plan, one line per node.
 */
internal fun JdbcTransaction.plan(statement: StatementBuilder.() -> Statement<*>): String =
    explain(analyze = true, options = "BUFFERS", body = statement).joinToString("\n") {
        it.toString().removePrefix("QUERY PLAN=")
    }
