// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcPreparedStatementImpl
import java.sql.Connection

/**
 * Runs [sent] again on this transaction's connection, [prefix] (`EXPLAIN `, say) before its text: the same text and
 * the same values, bound by their column types as Exposed bound them, so that its plan is the one the call's own query
 * had. Sent past Exposed, so that a recording running meanwhile does not note it.
 *
 * @param sent A statement a [StatementRecorder] noted.
 * @param prefix What goes before its text.
 *
 * @return The first column of each row it returns (a plan's lines), empty for a statement that returns none.
 */
fun JdbcTransaction.replayRecorded(sent: SentStatement, prefix: String = ""): List<String> =
    (connection.connection as Connection).prepareStatement(prefix + sent.sql).use { statement ->
        JdbcPreparedStatementImpl(statement, wasGeneratedKeysRequested = false).fillParameters(sent.args)
        if (statement.execute()) {
            statement.resultSet.use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        } else {
            emptyList()
        }
    }

/**
 * These statements by transaction, in order: each group the statements one transaction ran.
 *
 * @return The groups.
 */
fun List<SentStatement>.byTransaction(): List<List<SentStatement>> =
    fold(mutableListOf<MutableList<SentStatement>>()) { groups, sent ->
        if (groups.lastOrNull()?.let { it.last().transaction == sent.transaction } == true) {
            groups.last() += sent
        } else {
            groups += mutableListOf(sent)
        }
        groups
    }
