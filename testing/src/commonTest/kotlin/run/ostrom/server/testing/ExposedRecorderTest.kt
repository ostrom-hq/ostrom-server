// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.database.JdbcDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import kotlin.test.Test
import kotlin.test.assertEquals

/** An Exposed statement is replayed by sqlx4k as it was sent, its arrays included. */
class ExposedRecorderTest {
    @Test
    fun anArrayIsReplayedInItsTextFormCastBackToItsType() = runTest {
        assertEquals("{\"a\",\"b\\\"c\",\"d\\\\e\"}", ExposedRecorder.replayable(listOf("a", "b\"c", "d\\e")))
        assertEquals("x", ExposedRecorder.replayable("x"))
        assertEquals("SELECT ?", ExposedRecorder.replayableSql("SELECT ?", listOf(TextColumnType())))
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { jdbc ->
                    // The type's name is the dialect's: asked in a transaction, as the recorder is.
                    val (sql, type) = jdbc.query {
                        val array = ArrayColumnType<String, List<String>>(TextColumnType())
                        ExposedRecorder.replayableSql(
                            "SELECT '?' WHERE a = ANY (?) AND b = ? AND c = ?",
                            listOf(array, TextColumnType()),
                        ) to array.sqlType()
                    }
                    assertEquals(
                        "SELECT '?' WHERE a = ANY (CAST(? AS $type)) AND b = ? AND c = ?",
                        sql,
                        "Only the arrays' placeholders, never a quoted question mark",
                    )
                }
            } finally {
                database.close()
            }
        }
    }
}
