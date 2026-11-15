// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.ReturningBlockingExecutable

/**
 * Runs the `RETURNING` write built by [body] in this transaction and gives the rows it returns: the plan tests explain
 * the same builder.
 *
 * @param body The statement.
 *
 * @return The rows it changed, as it returns them.
 */
internal fun JdbcTransaction.returned(body: StatementBuilder.() -> ReturningStatement): List<ResultRow> =
    ReturningBlockingExecutable(buildStatement(body)).toList()
