// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.config.DatabaseConfig
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The JVM database layer's pool and session settings (CRL-01, CRL-02), and its transactions on PostgreSQL. */
class JdbcDatabaseTest {
    private val config = DatabaseConfig("postgresql://db.internal:5432/ostrom", "ostrom_app", "secret", 10)

    @Test
    fun theUrlCarriesTheDriverTimeoutsAndTheSessionTimeouts() {
        val url = JdbcDatabase.jdbcUrl(config)
        assertTrue(url.startsWith("jdbc:postgresql://db.internal:5432/ostrom?"), url)
        for (option in listOf("connectTimeout=5", "loginTimeout=5", "socketTimeout=15", "tcpKeepAlive=true")) {
            assertTrue(option in url, url)
        }
        assertTrue("ApplicationName=ostrom-server" in url && "reWriteBatchedInserts=true" in url, url)
        val session = "-c%20statement_timeout%3D10s%20-c%20lock_timeout%3D5s" +
            "%20-c%20idle_in_transaction_session_timeout%3D15s"
        assertTrue("options=$session" in url, url)
    }

    @Test
    fun thePoolIsBoundedAndWaitsLittle() {
        val hikari = JdbcDatabase.hikariConfig(config, 10)
        assertEquals(10, hikari.maximumPoolSize)
        assertEquals(1, hikari.minimumIdle)
        assertEquals(5_000L, hikari.connectionTimeout)
        assertEquals(60_000L, hikari.leakDetectionThreshold)
        assertEquals(false, hikari.isAutoCommit)
        assertEquals("TRANSACTION_READ_COMMITTED", hikari.transactionIsolation)
        assertEquals("ostrom_app", hikari.username)
        for (size in listOf(1, 65)) {
            assertFailsWith<IllegalArgumentException> { JdbcDatabase.hikariConfig(config, size) }
        }
        JdbcDatabase.hikariConfig(config, JdbcDatabase.MIN_POOL_SIZE)
        JdbcDatabase.hikariConfig(config, JdbcDatabase.MAX_POOL_SIZE)
    }

    @Test
    fun aPooledConnectionHasTheSessionTimeouts() {
        runTest {
            val url = System.getenv("OSTROM_TEST_DATABASE_URL") ?: return@runTest skipped()
            JdbcDatabase(DatabaseConfig.of(url, null, null, 2), 2).let { database ->
                val settings = database.query {
                    listOf("statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout").map { show(it) }
                }
                assertEquals(listOf("10s", "5s", "15s"), settings)
                assertEquals("read committed", database.query { show("transaction_isolation") })
                assertEquals(PoolState(open = true, waiting = 0), database.state())
                database.close()
                assertFalse(database.state().open)
            }
        }
    }

    private fun JdbcTransaction.show(setting: String): String =
        exec("SHOW $setting") { result -> result.next(); result.getString(1) } ?: fail("SHOW $setting answered nothing")

    private fun skipped() {
        if (System.getenv("OSTROM_TEST_DATABASE_REQUIRED") == "true") fail("OSTROM_TEST_DATABASE_URL is required")
    }
}
