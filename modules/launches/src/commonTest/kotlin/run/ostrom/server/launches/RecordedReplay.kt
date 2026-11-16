// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.testing.SentStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A statement the test server recorded (SentStatement: its `?` text and its values by position), run again on a JDBC
// transaction: the same text, the same values, so that its plan is the one the call's own query had.

/**
 * Runs [sent] on this transaction's connection, [prefix] (`EXPLAIN `, say) before its text.
 *
 * @return The first column of each row it returns (a plan's lines), empty for a statement that returns none.
 */
internal fun JdbcTransaction.replayRecorded(sent: SentStatement, prefix: String = ""): List<String> {
    val connection = connection.connection as Connection
    return connection.prepareStatement(prefix + sent.sql).use { statement ->
        sent.statement?.positionalParametersValues.orEmpty().forEachIndexed { index, value ->
            val bound = jdbcValue(value)
            if (bound == null) statement.setNull(index + 1, Types.NULL) else statement.setObject(index + 1, bound)
        }
        if (statement.execute()) {
            statement.resultSet.use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        } else {
            emptyList()
        }
    }
}

private fun jdbcValue(value: Any?): Any? = when (value) {
    is Uuid -> java.util.UUID.fromString(value.toString())
    is Instant -> OffsetDateTime.ofInstant(
        java.time.Instant.ofEpochSecond(value.epochSeconds, value.nanosecondsOfSecond.toLong()),
        ZoneOffset.UTC,
    )
    else -> value
}
